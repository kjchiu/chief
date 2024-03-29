package ca.caseybanner.chief;

import ca.caseybanner.chief.commands.ConfigurationException;
import ca.caseybanner.chief.commands.HelpCommand;
import ca.caseybanner.chief.commands.LCBOCommand;
import ca.caseybanner.chief.commands.MemeCommand;
import ca.caseybanner.chief.commands.QuitCommand;
import ca.caseybanner.chief.commands.YouTubeCommand;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jivesoftware.smack.Chat;
import org.jivesoftware.smack.ChatManager;
import org.jivesoftware.smack.ChatManagerListener;
import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.MessageListener;
import org.jivesoftware.smack.Roster;
import org.jivesoftware.smack.RosterEntry;
import org.jivesoftware.smack.SmackConfiguration;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Packet;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smackx.muc.DiscussionHistory;
import org.jivesoftware.smackx.muc.MultiUserChat;

/**
 * Created by kcbanner on 7/24/2014.
 */
public class Bot implements ChatManagerListener, MessageListener {

	private static final Logger logger = LogManager.getLogger(Bot.class);
	
	private final Lock lock;
	private final Condition running;	
	private final XMPPConnection connection;
	private final Properties properties;
	private final String username;
	private final String password;
	private final String conferenceHost;
	private final String nickname;
	private final Pattern roomPrefixPattern;
	private final List<String> rooms;
	private final List<String> admins;

	private final ConcurrentHashMap<String, MultiUserChat> multiUserChatsByRoom;
	private final List<Command> commands;

	/**
	 * List of properties that are required
	 */
	private static final List<String> requiredProperties = Arrays.asList(
			"host",
			"port",
			"username",
			"password",
			"conferenceHost"
	);
	
	/**
	 * Bot constructor
	 * 
	 * @param properties 
	 * @throws ca.caseybanner.chief.commands.ConfigurationException 
	 */
	public Bot(Properties properties) throws ConfigurationException {

		lock = new ReentrantLock();
		running = lock.newCondition();

		this.properties = properties;
		
		// Make sure the required properties exist
		
		for (String requiredProperty : requiredProperties) {
			if (properties.getProperty(requiredProperty) == null) {
				throw new ConfigurationException(
						"Missing required configuration property: " + requiredProperty);
			}			
		}	
		
		String host = properties.getProperty("host");
		int port = Integer.parseInt(properties.getProperty("port"));			
		this.username = properties.getProperty("username");
		this.password = properties.getProperty("password");	
		this.conferenceHost = properties.getProperty("conferenceHost");		

		this.nickname = properties.getProperty("nickname", "Chief Bot");		
		this.roomPrefixPattern = Pattern.compile(properties.getProperty(
				"roomPrefix", "^!\\s*"));

		ConnectionConfiguration config = new ConnectionConfiguration(host, port);
		
		// Load admins
		
		String adminsString = properties.getProperty("admins");
		if (adminsString == null) {
			admins = Collections.emptyList();
		} else {
			admins = Arrays.asList(adminsString.split(","));
		}
		
		// Load rooms
		
		String roomsString = properties.getProperty("rooms");
		if (roomsString == null) {
			rooms = Collections.emptyList();
		} else {
			rooms = Arrays.asList(roomsString.split(","));
		}
		
		multiUserChatsByRoom = new ConcurrentHashMap<>();		
		connection = new XMPPTCPConnection(config);		

		// Add some default commands
		
		commands = new ArrayList<>();		
		
		// TODO: Load commands based on properties file
		
		addCommand(HelpCommand::new);
		addCommand(QuitCommand::new);
		addCommand(YouTubeCommand::new);
		addCommand(LCBOCommand::new);
		addCommand(MemeCommand::new);
		
	}
	
	/**
	 * Constructs and adds a command
	 * 
	 * @param commandConstructor 
	 */
	private void addCommand(Function<Bot, Command> commandConstructor) {
		
		Command command = commandConstructor.apply(this);

		// Load any properties prefixed with this classname and apply them
		
		String typeName = command.getClass().getTypeName();	
		properties.stringPropertyNames().stream().filter(propertyName -> {
			
			// Filter properties that are prefixed with the full type name,
			// a period, and then at least one character
			
			return propertyName.indexOf(typeName) == 0 && 
					propertyName.length() > typeName.length() + 1 &&
					propertyName.charAt(typeName.length()) == '.';
		}).forEach(propertyName -> {			
			String fieldName = propertyName.substring(typeName.length() + 1);
			
			// Construct the name of the setter for this field
			
			StringBuilder setterNameBuilder = new StringBuilder("set");
			setterNameBuilder.append(fieldName.substring(0, 1).toUpperCase());				
			if (fieldName.length() > 1) {			
				setterNameBuilder.append(fieldName.substring(1));
			}
			
			String setterName = setterNameBuilder.toString();
			
			try {				
				
				// Call the setter with the property value
				
				Method method = command.getClass().getMethod(setterName, String.class);
				method.invoke(command, properties.getProperty(propertyName));
			} catch (NoSuchMethodException | SecurityException ex) {
				logger.error(
					"Could not find configuration setter {} on {}",
					setterName,
					command.getClass().getTypeName());				
			} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException ex) {
				logger.error(
					"Error calling configuration setter {} on {}",
					setterName,
					command.getClass().getTypeName(),
					ex);				
			}			
		});
		
		try {
			command.configurationComplete();
			commands.add(command);
		} catch (ConfigurationException e) {
			logger.error("Command configuration error in {}: {}",
					command.getClass().toString(), e.getMessage());
		}
		
	}
	
	/**
	 * Getter for the list of commands.
	 * 
	 * @return 
	 */
	public List<Command> getCommands() {
		return Collections.unmodifiableList(commands);
	}
	
	/**
	 * Connect to the server
	 */
	public void start() {		

		try {
			connection.connect();
			connection.login(username, password, "Chief Bot");
			
			logger.info("Bot online: connected to domain {}", connection.getConnectionID());

            ChatManager chatManager = ChatManager.getInstanceFor(connection);
            chatManager.addChatListener(this);        

			// Roster setup
			
			Roster roster = connection.getRoster();
			roster.setSubscriptionMode(Roster.SubscriptionMode.accept_all);		
			
			rooms.stream().forEach(roomName -> {				
				joinRoom(roomName);
			});
			
		} catch (XMPPException e) {
			throw new RuntimeException("XMPP Error", e);
		} catch (SmackException e) {
			throw new RuntimeException("Smack Error", e);
		} catch (IOException e) {
			throw new RuntimeException("IO Error", e);
		}
		
	}
	
	/**
	 * Waits until the bot exits before returning
	 */
	public void waitForExit() {

		lock.lock();
		try {		
			running.await();			
			connection.disconnect();
		} catch (InterruptedException | SmackException.NotConnectedException ex) {
			
		} finally {
			lock.unlock();
		}
	}
	
	/**
	 * Tell the bot to shutdown. 
	 * This will cause the call to waitForExit to return.
	 */
	public void exit() {
		lock.lock();
		
		try {
			running.signal();
		} finally {
			lock.unlock();
		}
		
	}

	/**
	 * Join a room
	 * 
	 * @param room
	 * @param nickname
	 * @return 
	 */
	private boolean joinRoom(String room) {
		logger.info("Join room \"{}\"", room);

		if (multiUserChatsByRoom.containsKey(room)) {

			// Already joined this room

			return true;
		}

		// Join the room, request 0 lines of history
		
		MultiUserChat muc = new MultiUserChat(connection, room + "@" + conferenceHost);

		DiscussionHistory history = new DiscussionHistory();
		history.setMaxStanzas(0);

		try {		
			muc.join(nickname, "", history, SmackConfiguration.getDefaultPacketReplyTimeout());
			muc.addMessageListener((Packet packet) -> {				
				processRoomMessage(muc, packet);
			});
			
			multiUserChatsByRoom.put(room, muc);

			return true;
		} catch (XMPPException.XMPPErrorException |
				SmackException.NoResponseException |
				SmackException.NotConnectedException e) {
			logger.error("Error joining room", e);			
			return false;
		}
		
	}
	
	/**
	 * Process a message and return an optional response
	 * 
	 * @param fromJID the JID this message was from
	 * @param message
	 * @param fromRoom
	 * @return 
	 */
	private Optional<String> handleMessage(String fromJID, Message message, boolean fromRoom) {

		// Ignore messages from ourselves (like posting things to a room we are in)

		if (fromRoom && fromJID.endsWith(nickname)) {
			return Optional.empty();
		}

		boolean isAdmin = admins.contains(XMPP.getPlainJID(fromJID));
		
		String body = message.getBody();
		if (body != null) {
			Optional<String> response = Optional.empty();
			
			// Room messages require a prefix to be processed. 
			// Check for it, then remove it before passing the message to the command.			
			
			if (fromRoom) {						
				Matcher prefixMatcher = roomPrefixPattern.matcher(body);
				if (prefixMatcher.find()&& prefixMatcher.end() < body.length()) {
					body = body.substring(prefixMatcher.end());
				} else {					
					return Optional.empty();
				}
			}			
			
			for (Command command : commands) {

				// Return the result of the first matching command

				Matcher matcher = command.getPattern().matcher(body);
				if (matcher.matches()) {
					if (command.isAdminOnly() && ! isAdmin) {
						response = Optional.of("This is an admin only command. Get out.");
					} else {
						response = command.processMessage(
								fromJID, message.getBody(), matcher, fromRoom);						
					}
					
					break;
				}
			}
			
			// If this a response back to a room, prefix it with the username that sent the command
			
			if (fromRoom && response.isPresent()) {
				
				// In MUCs, the resource is the nickname
				
				String fromNickname = fromJID.split("/")[1];
				response = Optional.of(
						"@" + nicknameToMentionName(fromNickname) +  " " + response.get());
				
			}			
			
			return response;
		}
		
		// No commands matched, if this is a single user chat then help them out
		
		if (! fromRoom) {
			return Optional.of("I didn't understand that. Type `help` for usage information.");			
		}
		
		return Optional.empty();
		
	}
	
	/**
	 * Convert a user's JID to a nickname using the roster.
	 * This does not work for JIDs from MUCs.
	 * 
	 * @param jid
	 * @return 
	 */
	private String jidToNickname(String jid) {
		String withoutResource = XMPP.getPlainJID(jid);
		
        for (RosterEntry r : connection.getRoster().getEntries()) {			
			if (r.getUser().equals(withoutResource)) {
				return r.getName();
			}
        }

		return jid;
	}
	
	/**
	 * Converts a nickname to a JID by looking it up in the roster.
	 * 
	 * @param nickname
	 * @return 
	 */
	private String nicknameToJID(String nickname) {	
        for (RosterEntry r : connection.getRoster().getEntries()) {
            if (r.getName().equals(nickname))
                return r.getUser();
        }
		
        return nickname;
    }
	
	/**
	 * Attempts to convert a nickname to a HipChat mention name.
	 * For now, this just removes all spaces. This works for most HipChat 
	 * names, unlesss the user has a set a custom name. Since Smack can't parse
	 * out the `mention_name` attribute in the `<item>` tag inside the roster
	 * <query>, this is good enough.
	 * 
	 * @param nickname
	 * @return 
	 */
	private String nicknameToMentionName(String nickname) {
		return nickname.replaceAll("\\s", "");
	}
	
	/**
	 * Send a message to a chat
	 * 
	 * @param chat
	 * @param message 
	 */
	private void sendMessage(Chat chat, String message) {
		try {
			chat.sendMessage(message);
		} catch (XMPPException | SmackException.NotConnectedException ex) {
			logger.error("Error sending message to {}", chat.getParticipant(), ex);
		}
	}

	/**
	 * Send a message to a multi user chat
	 * 
	 * @param chat
	 * @param message 
	 */
	private void sendMessage(MultiUserChat chat, String message) {
		try {
			chat.sendMessage(message);
		} catch (XMPPException | SmackException.NotConnectedException ex) {
			logger.error("Error sending message to room {}", chat.getNickname(), ex);
		}
	}
	
	/**
     * Called when a new chat is created with the bot
     * 
     * @param chat
     * @param createdLocally 
     */
    @Override
    public void chatCreated(Chat chat, boolean createdLocally) {
        
        logger.trace("Chat started with: {}", chat.getParticipant());
        chat.addMessageListener(this);
        
    }
	
	/**
	 *
	 * @param chat
	 * @param message
	 */
	@Override
	public void processMessage(Chat chat, Message message) {		

		if (message.getBody() != null) {
			logger.trace("Message from `{}`: {}", chat.getParticipant(), message.getBody());

			Optional<String> response = handleMessage(chat.getParticipant(), message, false);
			
			response.ifPresent(responseString -> {
				sendMessage(chat, responseString);		
			});
		}
		
	}
	
	private void processRoomMessage(MultiUserChat chat, Packet packet) {
		if (packet instanceof Message) {
			Message message = (Message) packet;
			logger.trace("MUC Message `{}`: {}", jidToNickname(message.getFrom()), message.getBody());
			
			// In rooms, the JID is room@domain/nickname
			// Messages not send by a user won't have a resource.
			
			String fromJID = message.getFrom();
			if (fromJID.contains("/")) {	
				Optional<String> response = handleMessage(fromJID, message, true);			

				response.ifPresent(responseString -> {
					sendMessage(chat, responseString);		
				});
			}			
		} else if (packet instanceof Presence) {
			Presence presence = (Presence) packet;
			logger.trace("MUC Presence `{}`: {}", chat.getRoom(), presence.getType().toString());						
		} else {
			logger.trace("MUC Packet:", packet.toXML());			
		}
		
	}

}
