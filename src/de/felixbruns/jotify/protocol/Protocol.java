package de.felixbruns.jotify.protocol;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import de.felixbruns.jotify.crypto.DH;
import de.felixbruns.jotify.exceptions.ConnectionException;
import de.felixbruns.jotify.exceptions.ProtocolException;
import de.felixbruns.jotify.media.Playlist;
import de.felixbruns.jotify.media.Track;
import de.felixbruns.jotify.protocol.channel.Channel;
import de.felixbruns.jotify.protocol.channel.ChannelListener;
import de.felixbruns.jotify.util.DNS;
import de.felixbruns.jotify.util.Hex;
import de.felixbruns.jotify.util.IntegerUtilities;

public class Protocol {
	/* Socket connection to Spotify server. */
	private SocketChannel channel;
	
	/* Current server and port */
	private InetSocketAddress server;
	
	/* Spotify session of this protocol instance. */
	private Session session;
	
	/* Protocol listeners. */
	private List<CommandListener> listeners;
	
	/* Create a new protocol object. */
	public Protocol(Session session){
		this.session   = session;
		this.listeners = new ArrayList<CommandListener>();
	}
	
	/* Connect to one of the spotify servers. */
	public void connect() throws ConnectionException {
		/* Lookup servers via DNS SRV query. */
		List<InetSocketAddress> servers = DNS.lookupSRV("_spotify-client._tcp.spotify.com");
		
		/* Add a fallback server if others don't work. */
		servers.add(new InetSocketAddress("ap.spotify.com", 4070));
		
		/* Try to connect to each server, stop trying when connected. */
		for(InetSocketAddress server : servers){
			try{
				/* Connect to server. */
				this.channel = SocketChannel.open(server);
				
				/* Save server for later use. */
				this.server = server;
				
				break;
			}
			catch(IOException e){
				throw new ConnectionException("Error connecting to '" + server + "': " + e.getMessage());
			}
		}
		
		/* If connection was not established, return false. */
		if(this.channel == null){
			throw new ConnectionException("Failed to connect!");
		}
		
		System.out.format("Connected to '%s'\n", this.server);
	}
	
	/* Disconnect from server */
	public void disconnect() throws ConnectionException {
		try{
			/* Close connection to server. */
			this.channel.close();
			
			System.out.format("Disconnected from '%s'\n", this.server);
		}
		catch(IOException e){
			throw new ConnectionException("Error disconnecting from '" + this.server + "': " + e.getMessage());
		}
	}
	
	public void addListener(CommandListener listener){
		this.listeners.add(listener);
	}
	
	/* Send initial packet (key exchange). */
	public void sendInitialPacket() throws ProtocolException {
		ByteBuffer buffer = ByteBuffer.allocate(
			2 + 2 + 4 + 4 + 4 + 4 + 4 + 4 + 4 + 16 + 96 + 128 + 1 + 1 + 2 + 0 + this.session.username.length + 1
		);
		
		/* Append fields to buffer. */
		buffer.putShort((short)3); /* Version: 3 */
		buffer.putShort((short)0); /* Length (update later) */
		buffer.putInt(0x00000000); /* Unknown */
		buffer.putInt(0x00030C00); /* Unknown */
		buffer.putInt(this.session.clientRevision);
		buffer.putInt(0x00000000); /* Unknown */
		buffer.putInt(0x01000000); /* Unknown */
		buffer.put(this.session.clientId); /* 4 bytes */
		buffer.putInt(0x00000000); /* Unknown */
		buffer.put(this.session.clientRandom); /* 16 bytes */
		buffer.put(this.session.dhClientKeyPair.getPublicKeyBytes()); /* 96 bytes */
		buffer.put(this.session.rsaClientKeyPair.getPublicKeyBytes()); /* 128 bytes */
		buffer.put((byte)0); /* Random length */
		buffer.put((byte)this.session.username.length); /* Username length */
		buffer.putShort((short)0x0100); /* Unknown */
		//buffer.put(randomBytes); /* Zero random bytes. */
		buffer.put(this.session.username);
		buffer.put((byte)0x40); /* Unknown */
		
		/* Update length byte. */
		buffer.putShort(2, (short)buffer.position());
		buffer.flip();
		
		/* Save initial client packet for auth hmac generation. */
		this.session.initialClientPacket = new byte[buffer.remaining()];
		
		buffer.get(this.session.initialClientPacket);
		buffer.flip();
		
		/* Send it. */
		this.send(buffer);
	}
	
	/* Receive initial packet (key exchange). */
	public void receiveInitialPacket() throws ProtocolException {
		byte[] buffer = new byte[512];
		int ret, paddingLength, usernameLength;
		
		/* Save initial server packet for auth hmac generation. 1024 bytes should be enough. */
		ByteBuffer serverPacketBuffer = ByteBuffer.allocate(1024);
		
		/* Read server random (first 2 bytes). */
		if((ret = this.receive(this.session.serverRandom, 0, 2)) == -1){
			throw new ProtocolException("Failed to read server random.");
		}
		
		/* Check if we got a status message. */
		if(this.session.serverRandom[0] != 0x00 || ret != 2){
			/*
			 * Substatuses:
			 * 0x01    : Client upgrade required.
			 * 0x03    : Non-existant user.
			 * 0x04    : Account has been disabled.
			 * 0x06    : You need to complete your account details.
			 * 0x09    : Your current country doesn't match that set in your profile.
			 * Default : Unknown error
			 */
			String message =
				this.session.serverRandom[1] == 0x01 ? "Client upgrade required: " :
				this.session.serverRandom[1] == 0x03 ? "Non-existant user." :
				this.session.serverRandom[1] == 0x04 ? "Account has been disabled." :
				this.session.serverRandom[1] == 0x06 ? "You need to complete your account details." :
				this.session.serverRandom[1] == 0x06 ? "Your current country doesn't match that set in your profile." :
				"Unknown error."
			;
			
			/* If substatus is 'Client upgrade required', read upgrade URL. */
			if(this.session.serverRandom[1] == 0x01){
				if((ret = this.receive(buffer, 0x11a)) > 0){
					paddingLength = buffer[0x119] & 0xFF;
					
					if((ret = this.receive(buffer, paddingLength)) > 0){
						message += new String(Arrays.copyOfRange(buffer, 0, ret));
					}
				}
			}
			
			throw new ProtocolException(message);
		}
		
		/* Read server random (next 14 bytes). */
		if((ret = this.receive(this.session.serverRandom, 2, 14)) != 14){
			throw new ProtocolException("Failed to read server random.");
		}
		
		/* Save server random to packet buffer. */
		serverPacketBuffer.put(this.session.serverRandom);
		
		/* Read server public key (Diffie Hellman key exchange). */
		if((ret = this.receive(buffer, 96)) != 96){
			throw new ProtocolException("Failed to read server public key.");
		}
		
		/* Save DH public key to packet buffer. */
		serverPacketBuffer.put(buffer, 0, 96);
		
		/* 
		 * Convert key, which is in raw byte form to a DHPublicKey
		 * using the DHParameterSpec (for P and G values) of our
		 * public key. Y value is taken from raw bytes.
		 */
		this.session.dhServerPublicKey = DH.bytesToPublicKey(
			this.session.dhClientKeyPair.getPublicKey().getParams(),
			Arrays.copyOfRange(buffer, 0, 96)
		);
		
		/* Read server blob (256 bytes). */
		if((ret = this.receive(this.session.serverBlob, 0, 256)) != 256){
			throw new ProtocolException("Failed to read server blob.");
		}
		
		/* Save RSA signature to packet buffer. */
		serverPacketBuffer.put(this.session.serverBlob);
		
		/* Read salt (10 bytes). */
		if((ret = this.receive(this.session.salt, 0, 10)) != 10){
			throw new ProtocolException("Failed to read salt.");
		}
		
		/* Save salt to packet buffer. */
		serverPacketBuffer.put(this.session.salt);
		
		/* Read padding length (1 byte). */
		if((paddingLength = this.receive()) == -1){
			throw new ProtocolException("Failed to read paddling length.");
		}
		
		/* Save padding length to packet buffer. */
		serverPacketBuffer.put((byte)paddingLength);
		
		/* Check if padding length is valid. */
		if(paddingLength <= 0){
			throw new ProtocolException("Padding length is negative or zero.");
		}
		
		/* Read username length. */
		if((usernameLength = this.receive()) == -1){
			throw new ProtocolException("Failed to read username length.");
		}
		
		/* Save username length to packet buffer. */
		serverPacketBuffer.put((byte)usernameLength);
		
		/* Read lengths of puzzle challenge and unknown fields */
		this.receive(buffer, 8);
		
		/* Save bytes to packet buffer. */
		serverPacketBuffer.put(buffer, 0, 8);
		
		/* Get lengths of puzzle challenge and unknown fields.  */
		ByteBuffer dataBuffer     = ByteBuffer.wrap(buffer, 0, 8);
		int puzzleChallengeLength = dataBuffer.getShort();
		int unknownLength1        = dataBuffer.getShort();
		int unknownLength2        = dataBuffer.getShort();
		int unknownLength3        = dataBuffer.getShort();
		
		/* Read padding. */
		if((ret = this.receive(buffer, paddingLength)) != paddingLength){
			throw new ProtocolException("Failed to read padding.");
		}
		
		/* Save padding (random bytes) to packet buffer. */
		serverPacketBuffer.put(buffer, 0, paddingLength);
		
		/* Read username into buffer and copy it to 'session.username'. */
		if((ret = this.receive(buffer, usernameLength)) != usernameLength){
			throw new ProtocolException("Failed to read username.");
		}
		
		/* Save username to packet buffer. */
		serverPacketBuffer.put(buffer, 0, usernameLength);
		
		/* Save username to session. */
		this.session.username = Arrays.copyOfRange(buffer, 0, usernameLength);
		
		/* Receive puzzle challenge and unknown bytes. */
		this.receive(buffer,                                                       0, puzzleChallengeLength);
		this.receive(buffer,                                   puzzleChallengeLength, unknownLength1);
		this.receive(buffer,                  puzzleChallengeLength + unknownLength1, unknownLength2);
		this.receive(buffer, puzzleChallengeLength + unknownLength1 + unknownLength2, unknownLength3);
		
		/* Save to packet buffer. */
		serverPacketBuffer.put(buffer, 0, puzzleChallengeLength + unknownLength1 + unknownLength2 + unknownLength3);
		serverPacketBuffer.flip();
		
		/* Write data from packet buffer to byte array. */
		this.session.initialServerPacket = new byte[serverPacketBuffer.remaining()];
		
		serverPacketBuffer.get(this.session.initialServerPacket);
		
		/* Wrap buffer in order to get values. */
		dataBuffer = ByteBuffer.wrap(buffer, 0, puzzleChallengeLength + unknownLength1 + unknownLength2 + unknownLength3);
		
		/* Get puzzle denominator and magic. */
		if(dataBuffer.get() == 0x01){			
			this.session.puzzleDenominator = dataBuffer.get();
			this.session.puzzleMagic       = dataBuffer.getInt();
		}
		else{
			throw new ProtocolException("Unexpected puzzle challenge.");
		}
	}
	
	/* Send authentication packet (puzzle solution, HMAC). */
	public void sendAuthenticationPacket() throws ProtocolException {
		ByteBuffer buffer = ByteBuffer.allocate(20 + 1 + 1 + 4 + 2 + 15 + 8);
		
		/* Append fields to buffer. */
		buffer.put(this.session.authHmac); /* 20 bytes */
		buffer.put((byte)0); /* Random data length */
		buffer.put((byte)0); /* Unknown. */
		buffer.putShort((short)this.session.puzzleSolution.length);
		buffer.putInt(0x0000000); /* Unknown. */
		//buffer.put(randomBytes); /* Zero random bytes :-) */
		buffer.put(this.session.puzzleSolution); /* 8 bytes */
		buffer.flip();
		
		/* Send it. */
		this.send(buffer);
	}
	
	/* Receive authentication packet (status). */
	public void receiveAuthenticationPacket() throws ProtocolException {
		byte[] buffer = new byte[512];
		int payloadLength;
		
		/* Read status and length. */
		if(this.receive(buffer, 2) != 2){
			throw new ProtocolException("Failed to read status and length bytes.");
		}
		
		/* Check status. */
		if(buffer[0] != 0x00){
			throw new ProtocolException("Authentication failed! (Error " + buffer[1] + ")");
		}
		
		/* Check payload length. AND with 0x00FF so we don't get a negative integer. */
		if((payloadLength = buffer[1] & 0xFF) <= 0){
			throw new ProtocolException("Payload length is negative or zero.");
		}
				
		/* Read payload. */
		if(this.receive(buffer, payloadLength) != payloadLength){
			throw new ProtocolException("Failed to read payload.");
		}
	}

	/* Send command with payload (will be encrypted with stream cipher). */
	public synchronized void sendPacket(int command, ByteBuffer payload) throws ProtocolException {
		ByteBuffer buffer = ByteBuffer.allocate(1 + 2 + payload.remaining());
		
		/* Set IV. */
		this.session.shannonSend.nonce(IntegerUtilities.toBytes(this.session.keySendIv));
		
		/* Build packet. */
		buffer.put((byte)command);
		buffer.putShort((short)payload.remaining());
		buffer.put(payload);
		
		byte[] bytes = buffer.array();
		byte[] mac   = new byte[4];
		
		/* Encrypt packet and get MAC. */
		this.session.shannonSend.encrypt(bytes);
		this.session.shannonSend.finish(mac);
		
		buffer = ByteBuffer.allocate(buffer.position() + 4);
		buffer.put(bytes);
		buffer.put(mac);
		buffer.flip();
		
		/* Send encrypted packet. */
		this.send(buffer);
		
		/* Increment IV. */
		this.session.keySendIv++;
	}
	
	/* Send a command without payload. */
	public void sendPacket(int command) throws ProtocolException {
		this.sendPacket(command, ByteBuffer.allocate(0));
	}
	
	/* Receive a packet (will be decrypted with stream cipher). */
	public void receivePacket() throws ProtocolException {
		byte[] header = new byte[3];
		int command, payloadLength, headerLength = 3, macLength = 4;
		
		/* Read header. */
		if(this.receive(header, headerLength) != headerLength){
			throw new ProtocolException("Failed to read header.");
		}
		
		/* Set IV. */
		this.session.shannonRecv.nonce(IntegerUtilities.toBytes(this.session.keyRecvIv));
		
		/* Decrypt header. */
		this.session.shannonRecv.decrypt(header);
		
		/* Get command and payload length from header. */
		ByteBuffer headerBuffer = ByteBuffer.wrap(header);
		
		command       = headerBuffer.get()      & 0xff;
		payloadLength = headerBuffer.getShort() & 0xffff;
		
		/* Allocate buffer. Account for MAC. */
		byte[]     bytes  = new byte[payloadLength + macLength];
		ByteBuffer buffer = ByteBuffer.wrap(bytes);
		
		/* Limit buffer to payload length, so we can read the payload. */
		buffer.limit(payloadLength);
		
		try{
			for(int n = payloadLength, r; n > 0 && (r = this.channel.read(buffer)) > 0; n -= r);
		}
		catch(IOException e){
			throw new ProtocolException("Failed to read payload: " + e.getMessage());
		}
		
		/* Extend it again to payload and mac length. */
		buffer.limit(payloadLength + macLength);
		
		try{
			for(int n = macLength, r; n > 0 && (r = this.channel.read(buffer)) > 0; n -= r);
		}
		catch(IOException e){
			throw new ProtocolException("Failed to read MAC: " + e.getMessage());
		}
		
		/* Decrypt payload. */
		this.session.shannonRecv.decrypt(bytes);
		
		/* Get payload bytes from buffer (throw away MAC). */
		byte[] payload = new byte[payloadLength];
		
		buffer.flip();
		buffer.get(payload);
		
		/* Increment IV. */
		this.session.keyRecvIv++;
		
		/* Fire events. */
		for(CommandListener listener : this.listeners){
			listener.commandReceived(command, payload);
		}
	}
	
	/* Send cache hash. */
	public void sendCacheHash() throws ProtocolException {
		ByteBuffer buffer = ByteBuffer.allocate(20);
		
		buffer.put(this.session.cacheHash);
		buffer.flip();
		
		this.sendPacket(Command.COMMAND_CACHEHASH, buffer);
	}
	
	/* Request ads. The response is GZIP compressed XML. */
	public void sendAdRequest(ChannelListener listener, int type) throws ProtocolException {
		/* Create channel and buffer. */
		Channel    channel = new Channel("Ad-Channel", Channel.Type.TYPE_AD, listener);
		ByteBuffer buffer  = ByteBuffer.allocate(2 + 1);
		
		/* Append channel id and ad type. */
		buffer.putShort((short)channel.getId());
		buffer.put((byte)type);
		buffer.flip();
		
		/* Register channel. */
		Channel.register(channel);
		
		/* Send packet. */
		this.sendPacket(Command.COMMAND_REQUESTAD, buffer);
	}
	
	/* Request image using a 20 byte id. The response is a JPG. */
	public void sendImageRequest(ChannelListener listener, String id) throws ProtocolException {
		/* Create channel and buffer. */
		Channel    channel = new Channel("Image-Channel", Channel.Type.TYPE_IMAGE, listener);
		ByteBuffer buffer  = ByteBuffer.allocate(2 + 20);
		
		/* Append channel id and image hash. */
		buffer.putShort((short)channel.getId());
		buffer.put(Hex.toBytes(id));
		buffer.flip();
		
		/* Register channel. */
		Channel.register(channel);
		
		/* Send packet. */
		this.sendPacket(Command.COMMAND_IMAGE, buffer);
	}
	
	/* Search music. The response comes as GZIP compressed XML. */
	public void sendSearchQuery(ChannelListener listener, String query, int offset, int limit) throws ProtocolException {
		/* Create channel and buffer. */
		Channel    channel = new Channel("Search-Channel", Channel.Type.TYPE_SEARCH, listener);
		ByteBuffer buffer  = ByteBuffer.allocate(2 + 4 + 4 + 2 + 1 + query.getBytes().length);
		
		/* Check offset and limit. */
		if(offset < 0){
			throw new IllegalArgumentException("Offset needs to be >= 0");
		}
		else if((limit < 0 && limit != -1) || limit == 0){
			throw new IllegalArgumentException("Limit needs to be either -1 for no limit or > 0");
		}
		
		/* Append channel id, some values, query length and query. */
		buffer.putShort((short)channel.getId());
		buffer.putInt(offset); /* Result offset. */
		buffer.putInt(limit); /* Reply limit. */
		buffer.putShort((short)0x0000);
		buffer.put((byte)query.length());
		buffer.put(query.getBytes());
		buffer.flip();
		
		/* Register channel. */
		Channel.register(channel);
		
		/* Send packet. */
		this.sendPacket(Command.COMMAND_SEARCH, buffer);
	}
	
	/* Search music. The response comes as GZIP compressed XML. */
	public void sendSearchQuery(ChannelListener listener, String query) throws ProtocolException {
		this.sendSearchQuery(listener, query, 0, -1);
	}
	
	/* Notify server we're going to play. */
	public void sendTokenNotify() throws ProtocolException {
		this.sendPacket(Command.COMMAND_TOKENNOTIFY);
	}
	
	/* Request AES key for a track. */
	public void sendAesKeyRequest(ChannelListener listener, Track track) throws ProtocolException {
		/* Create channel and buffer. */
		Channel    channel = new Channel("AES-Key-Channel", Channel.Type.TYPE_AESKEY, listener);
		ByteBuffer buffer  = ByteBuffer.allocate(20 + 16 + 2 + 2);
		
		/* Request the AES key for this file by sending the file id and track id. */
		buffer.put(Hex.toBytes(track.getFiles().get(0))); /* 20 bytes */
		buffer.put(Hex.toBytes(track.getId())); /* 16 bytes */
		buffer.putShort((short)0x0000);
		buffer.putShort((short)channel.getId());
		buffer.flip();
		
		/* Register channel. */
		Channel.register(channel);
		
		/* Send packet. */
		this.sendPacket(Command.COMMAND_REQKEY, buffer);
	}
	
	/* A demo wrapper for playing a track. */
	public void sendPlayRequest(ChannelListener listener, Track track) throws ProtocolException {
		/* 
		 * Notify the server about our intention to play music, there by allowing
		 * it to request other players on the same account to pause.
		 * 
		 * Yet another client side restriction to annony those who share their
		 * Spotify account with not yet invited friends. And as a bonus it won't
		 * play commercials and waste bandwidth in vain.
		 */
		this.sendPacket(Command.COMMAND_REQUESTPLAY);
		this.sendAesKeyRequest(listener, track);
	}
	
	/*
	 * Request a part of the encrypted file from the server.
	 * 
	 * The data should be decrypted using AES key in CTR mode
	 * with AES key provided and a static IV, incremented for
	 * each 16 byte data processed.
	 */
	public void sendSubstreamRequest(ChannelListener listener, Track track, int offset, int length) throws ProtocolException {
		/* Create channel and buffer. */
		Channel    channel = new Channel("Substream-Channel", Channel.Type.TYPE_SUBSTREAM, listener);
		ByteBuffer buffer  = ByteBuffer.allocate(2 + 2 + 2 + 2 + 2 + 2 + 4 + 20 + 4 + 4);
		
		/* Append channel id. */
		buffer.putShort((short)channel.getId());
		
		/* Unknown 10 bytes. */
		buffer.putShort((short)0x0800);
		buffer.putShort((short)0x0000);
		buffer.putShort((short)0x0000);
		buffer.putShort((short)0x0000);
		buffer.putShort((short)0x4e20);
		
		/* Unknown (static value) */
		buffer.putInt(200 * 1000);
		
		/* 20 bytes file id. */
		buffer.put(Hex.toBytes(track.getFiles().get(0)));
		
		if(offset % 4096 != 0 || length % 4096 != 0){
			throw new IllegalArgumentException("Offset and length need to be a multiple of 4096.");	
		}
		
		offset >>= 2;
		length >>= 2;
		
		/* Append offset and length. */
		buffer.putInt(offset);
		buffer.putInt(offset + length);
		buffer.flip();
		
		/* Register channel. */
		Channel.register(channel);
		
		/* Send packet. */
		this.sendPacket(Command.COMMAND_GETSUBSTREAM, buffer);
	}
	
	/*
	 * Get metadata for an artist (type = 1), album (type = 2) or a
	 * list of tracks (type = 3). The response comes as compressed XML.
	 */
	public void sendBrowseRequest(ChannelListener listener, int type, Collection<String> ids) throws ProtocolException {
		/* Create channel and buffer. */
		Channel    channel = new Channel("Browse-Channel", Channel.Type.TYPE_BROWSE, listener);
		ByteBuffer buffer  = ByteBuffer.allocate(2 + 1 + ids.size() * 16 + ((type == 1 || type == 2)?4:0));
		
		/* Check arguments. */
		if(type != 1 && type != 2 && type != 3){
			throw new IllegalArgumentException("Type needs to be 1, 2 or 3.");
		}
		else if((type == 1 && type == 2) && ids.size() != 1){
			throw new IllegalArgumentException("Types 1 and 2 only accept a single id.");
		}
		
		/* Append channel id and type. */
		buffer.putShort((short)channel.getId());
		buffer.put((byte)type);
		
		/* Append (16 byte) ids. */
		for(String id : ids){
			buffer.put(Arrays.copyOfRange(Hex.toBytes(id), 0, 16));
		}
		
		/* Append zero. */
		if(type == 1 || type == 2){
			buffer.putInt(0);
		}
		
		buffer.flip();
		
		/* Register channel. */
		Channel.register(channel);
		
		/* Send packet. */
		this.sendPacket(Command.COMMAND_BROWSE, buffer);
	}
	
	/* Browse with only one id. */
	public void sendBrowseRequest(ChannelListener listener, int type, String id) throws ProtocolException {
		ArrayList<String> list = new ArrayList<String>();
		
		list.add(id);
		
		this.sendBrowseRequest(listener, type, list);
	}
	
	/* Request playlist details. The response comes as plain XML. */
	public void sendPlaylistRequest(ChannelListener listener, String id) throws ProtocolException {
		/* Create channel and buffer. */
		Channel    channel = new Channel("Playlist-Channel", Channel.Type.TYPE_PLAYLIST, listener);
		ByteBuffer buffer  = ByteBuffer.allocate(2 + 17 + 4 + 4 + 4 + 1);
		
		/* Append channel id, playlist id and some bytes... */
		buffer.putShort((short)channel.getId());
		buffer.put(Hex.toBytes(id)); /* 17 bytes */
		buffer.putInt(-1); /* Playlist history. -1: current. 0: changes since version 0, 1: since version 1, etc. */
		buffer.putInt(0);
		buffer.putInt(-1);
		buffer.put((byte)0x01);
		buffer.flip();
		
		/* Register channel. */
		Channel.register(channel);
		
		/* Send packet. */
		this.sendPacket(Command.COMMAND_GETPLAYLIST, buffer);
	}
	
	/* Change playlist. The response comes as plain XML. */
	public void sendChangePlaylist(ChannelListener listener, Playlist playlist, String xml) throws ProtocolException {
		/* Create channel and buffer. */
		Channel    channel = new Channel("Change-Playlist-Channel", Channel.Type.TYPE_PLAYLIST, listener);
		ByteBuffer buffer  = ByteBuffer.allocate(2 + 17 + 4 + 4 + 4 + 1 + 1 + xml.getBytes().length);
		
		/* Append channel id, playlist id and some bytes... */
		buffer.putShort((short)channel.getId());
		buffer.put(Hex.toBytes(playlist.getId())); /* 17 bytes */
		buffer.putInt((int)playlist.getRevision());
		buffer.putInt(playlist.getTracks().size());
		buffer.putInt((int)playlist.getChecksum()); /* -1: Create playlist. */
		buffer.put((byte)(playlist.isCollaborative()?0x01:0x00));
		buffer.put((byte)0x03); /* Unknown */
		buffer.put(xml.getBytes());
		buffer.flip();
		
		/* Register channel. */
		Channel.register(channel);
		
		/* Send packet. */
		this.sendPacket(Command.COMMAND_CHANGEPLAYLIST, buffer);
	}
	
	/* Ping reply (pong). */
	public void sendPong() throws ProtocolException {
		ByteBuffer buffer = ByteBuffer.allocate(4);
		
		/* TODO: Append timestamp? */
		buffer.putInt(0x00000000);
		buffer.flip();
		
		/* Send packet. */
		this.sendPacket(Command.COMMAND_PONG, buffer);
	}
	
	/* Send bytes. */
	private void send(ByteBuffer buffer) throws ProtocolException {
		try{
			this.channel.write(buffer);
		}
		catch (IOException e){
			throw new ProtocolException("Error writing data to socket: " + e.getMessage());
		}
	}
	
	/* Receive a single byte. */
	private int receive() throws ProtocolException {
		ByteBuffer buffer = ByteBuffer.allocate(1);
		
		try{
			this.channel.read(buffer);
			
			buffer.flip();
			
			return buffer.get() & 0xff;
		}
		catch(IOException e){
			throw new ProtocolException("Error reading data from socket: " + e.getMessage());
		}
	}
	
	/* Receive bytes. */
	private int receive(byte[] buffer, int len) throws ProtocolException {
		return this.receive(buffer, 0, len);
	}
	
	/* Receive bytes. */
	private int receive(byte[] bytes, int off, int len) throws ProtocolException {
		ByteBuffer buffer = ByteBuffer.wrap(bytes, off, len);
		int n = 0;
		
		try{
			for(int r; n < len && (r = this.channel.read(buffer)) > 0; n += r);
		}
		catch(IOException e){
			throw new ProtocolException("Error reading data from socket: " + e.getMessage());
		}
		
		return n;
	}
}