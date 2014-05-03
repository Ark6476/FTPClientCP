package ark.FTPClientCP;

import it.sauronsoftware.ftp4j.FTPClient;
import it.sauronsoftware.ftp4j.FTPException;
import it.sauronsoftware.ftp4j.FTPIllegalReplyException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Properties;

public class FTPClientCP {
	private static FTPClientCP instance;
	private static Object syncRoot = new Object();
	private ArrayList<FTPClient> locked, unlocked;
	private Properties config;
	
	public FTPClientCP(Properties properties) {
		locked = new ArrayList<FTPClient>();
		unlocked = new ArrayList<FTPClient>();
		this.config = properties;
	}
	
	public Properties getProperties() {
		return this.config;
	}
	
	public FTPClient checkOut() throws IllegalStateException, IOException, FTPIllegalReplyException, FTPException, InterruptedException {
		FTPClient client = null;
		int sleepTimeMS = 500;
		while (true) {
			synchronized (syncRoot) {
				//Try to use an existing connection
				if (unlocked.size() > 0) {
					client = unlocked.remove(unlocked.size() - 1);
					try {
						client.noop();//Make sure the connection is still alive
					} catch (IllegalStateException | IOException | FTPIllegalReplyException | FTPException e) {
						this.disconnect(client);
						client = null;
					}
				}
				//Try to make a new connection
				if (null == client) {
					int maxConnections = Integer.parseInt(config.getProperty("maxConnections"));
					if (maxConnections < 1) {
						throw new IllegalStateException("maxConnections " + maxConnections + " must be larger than zero");
					}
					if (locked.size() + unlocked.size() <= maxConnections) {
						client = this.create();
					}
				}
				
				if (null != client) {//We have a connection
					locked.add(client);
					return client;
				}
			}
			//Wait for a connection to be returned
			Thread.sleep(sleepTimeMS);
			if (sleepTimeMS < 30000) {
				sleepTimeMS += 500;
			}
		}
	}
	
	public void checkIn(FTPClient client) {
		synchronized (syncRoot) {
			if (locked.remove(client)) {
				unlocked.add(client);
			}
		}
	}
	
	private FTPClient create() throws IllegalStateException, IOException, FTPIllegalReplyException, FTPException {
		FTPClient client = new FTPClient();
		String username = config.getProperty("username");
		String password = config.getProperty("password");
		String server = config.getProperty("server");
		int port = Integer.parseInt(config.getProperty("port"));
		boolean passivep = Boolean.parseBoolean(config.getProperty("passive"), "false");
		
		if (0 == port) {
			client.connect(server);
		}
		else {
			client.connect(server, port);
		}
		client.login(username, password);
		client.setPassive(passivep);
		client.noop();
		return client;
	}
	
	public void disconnectAll(long timeoutMS) throws InterruptedException, IllegalStateException, IOException, FTPIllegalReplyException, FTPException {
		long runningTimeMS = 0;
		long startTS = System.currentTimeMillis();
		while (unlocked.size() > 0 && runningTimeMS <= timeoutMS) {
			Thread.sleep(1000);
			runningTimeMS = System.currentTimeMillis() - startTS;
		}
		synchronized (syncRoot) {
			for (int i = locked.size() - 1; i >= 0; --i) {
				disconnect(locked.get(i));
			}
			locked = new ArrayList<FTPClient>();
			for (int i = unlocked.size() - 1; i >= 0; --i) {
				disconnect(unlocked.get(i));
			}
			unlocked = new ArrayList<FTPClient>();
		}
	}
	
	private void disconnect(FTPClient client) throws IllegalStateException, IOException, FTPIllegalReplyException, FTPException {
		try {
			if (client.isConnected()) {
				client.disconnect(true);
			}
		} catch (IOException | IllegalStateException | FTPIllegalReplyException | FTPException e) {
			if (client.isConnected()) {
				client.disconnect(false);
			}
		}
	}
}
