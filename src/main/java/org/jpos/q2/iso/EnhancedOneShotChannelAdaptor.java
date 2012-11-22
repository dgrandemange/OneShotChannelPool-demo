package org.jpos.q2.iso;

import java.io.IOException;

import org.jdom.Element;
import org.jpos.core.ConfigurationException;
import org.jpos.iso.Channel;
import org.jpos.iso.FactoryChannel;
import org.jpos.iso.FilteredChannel;
import org.jpos.iso.ISOChannel;
import org.jpos.iso.ISOClientSocketFactory;
import org.jpos.iso.ISOFilter;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.ISOPackager;
import org.jpos.iso.ISOUtil;
import org.jpos.q2.QBeanSupport;
import org.jpos.q2.QFactory;
import org.jpos.space.Space;
import org.jpos.space.SpaceFactory;
import org.jpos.util.LogSource;
import org.jpos.util.NameRegistrar;

/**
 * Inspired from jpos original org.jpos.q2.iso.OneShotChannelAdaptor<BR>
 * 
 * New optinal configuration element &lt;cnx-process-handling&gt;
 * (default=false) indicates if connection process should be handled so that when a
 * connection attempt fails, a connection failure exception is thrown to the
 * send() caller<BR>
 * 
 * @author dgrandemange
 * 
 */
public class EnhancedOneShotChannelAdaptor extends QBeanSupport implements
		EnhancedOneShotChannelAdaptorMBean, Channel {
	Space<String, Object> sp;
	String in, out, cnxHandlingQueue;
	boolean cnxProcessHandling;
	long delay;
	int maxConnections;
	int maxConnectAttempts;

	public EnhancedOneShotChannelAdaptor() {
		super();
	}

	@SuppressWarnings("unchecked")
	private Space<String, Object> grabSpace(Element e) {
		return (Space<String, Object>) SpaceFactory.getSpace(e != null ? e
				.getText() : "");
	}

	public void initAdaptor() {
		Element persist = getPersist();
		sp = grabSpace(persist.getChild("space"));
		in = persist.getChildTextTrim("in");
		out = persist.getChildTextTrim("out");

		String sCnxProcessHandling = persist
				.getChildTextTrim("cnx-process-handling");
		if (null != sCnxProcessHandling) {
			cnxProcessHandling = Boolean.parseBoolean(sCnxProcessHandling);
		} else {
			cnxProcessHandling = false;
		}
		cnxHandlingQueue = String.format("%s_cnx-handling-queue",
				this.getName());

		delay = 5000;

		String s = persist.getChildTextTrim("max-connections");
		maxConnections = (s != null) ? Integer.parseInt(s) : 1; // reasonable
																// default
		s = persist.getChildTextTrim("max-connect-attempts");
		maxConnectAttempts = (s != null) ? Integer.parseInt(s) : 15; // reasonable
																		// default
	}

	public void startService() {
		try {
			initAdaptor();
			for (int i = 0; i < maxConnections; i++) {
				Worker w = new Worker(i);
				w.initChannel();
				(new Thread(w)).start();
			}
			NameRegistrar.register(getName(), this);
		} catch (Exception e) {
			getLog().warn("error starting service", e);
		}
	}

	public void stopService() {
		try {
			for (int i = 0; i < maxConnections; i++) {
				sp.out(in, new Object());
			}
		} catch (Exception e) {
			getLog().warn("error stopping service", e);
		}
	}

	public void destroyService() {
		NameRegistrar.unregister(getName());
		NameRegistrar.unregister("channel." + getName());
	}

	/**
	 * Queue a message to be transmitted by this adaptor
	 * 
	 * @param m
	 *            message to send
	 */
	public void send(ISOMsg m) {
		sp.out(in, m);

		handleConnection();
	}

	/**
	 * Queue a message to be transmitted by this adaptor
	 * 
	 * @param m
	 *            message to send
	 * @param timeout
	 *            in millis
	 */
	public void send(ISOMsg m, long timeout) {
		sp.out(in, m, timeout);

		handleConnection();
	}

	private void handleConnection() {
		if (isConnectionProcessHandled()) {
			Object obj = sp.in(cnxHandlingQueue);

			if (obj != null) {
				if (obj instanceof ConnectionFailureException) {
					throw (ConnectionFailureException) obj;
				}
			}
		}
	}

	/**
	 * Receive message
	 */
	public ISOMsg receive() {
		return (ISOMsg) sp.in(out);
	}

	/**
	 * Receive message
	 * 
	 * @param timeout
	 *            time to wait for an incoming message
	 */
	public ISOMsg receive(long timeout) {
		return (ISOMsg) sp.in(out, timeout);
	}

	public class Worker implements Runnable {
		ISOChannel channel;
		int id;

		public Worker(int i) {
			super();
			id = i;
		}

		public void run() {
			Thread.currentThread().setName("channel-worker-" + id);
			int[] handbackFields = cfg.getInts("handback-field");
			while (running()) {
				try {
					Object o = sp.in(in, delay);
					if (o instanceof ISOMsg) {
						ISOMsg m = (ISOMsg) o;
						ISOMsg handBack = null;
						if (handbackFields.length > 0)
							handBack = (ISOMsg) m.clone(handbackFields);
						for (int i = 0; !channel.isConnected()
								&& i < maxConnectAttempts; i++) {
							try {
								channel.reconnect();
								if (!channel.isConnected())
									ISOUtil.sleep(1000L);
							} catch (IOException e) {
								if (isConnectionProcessHandled()) {
									sp.out(cnxHandlingQueue,
											new ConnectionFailureException(e));
								}
								throw e;
							}
						}
						if (channel.isConnected()) {
							if (isConnectionProcessHandled()) {
								sp.out(cnxHandlingQueue, Boolean.TRUE);
							}

							channel.send(m);
							m = channel.receive();
							channel.disconnect();
							if (handBack != null)
								m.merge(handBack);
							sp.out(out, m);
						} else {
							if (isConnectionProcessHandled()) {
								sp.out(cnxHandlingQueue,
										new ConnectionFailureException());
							}
						}
					}
				} catch (Exception e) {
					getLog().warn("channel-worker-" + id, e.getMessage());
					ISOUtil.sleep(1000);
				} finally {
					try {
						channel.disconnect();
					} catch (Exception e) {
						getLog().warn("channel-worker-" + id, e.getMessage());
					}
				}
			}
		}

		public void initChannel() throws ConfigurationException {
			Element persist = getPersist();
			Element e = persist.getChild("channel");
			if (e == null)
				throw new ConfigurationException("channel element missing");

			channel = newChannel(e, getFactory());

			String socketFactoryString = getSocketFactory();
			if (socketFactoryString != null
					&& channel instanceof FactoryChannel) {
				ISOClientSocketFactory sFac = (ISOClientSocketFactory) getFactory()
						.newInstance(socketFactoryString);
				if (sFac != null && sFac instanceof LogSource) {
					((LogSource) sFac).setLogger(log.getLogger(), getName()
							+ ".socket-factory");
				}
				getFactory().setConfiguration(sFac, e);
				((FactoryChannel) channel).setSocketFactory(sFac);
			}

		}

		private ISOChannel newChannel(Element e, QFactory f)
				throws ConfigurationException {
			String channelName = e.getAttributeValue("class");
			if (channelName == null)
				throw new ConfigurationException(
						"class attribute missing from channel element.");

			String packagerName = e.getAttributeValue("packager");

			ISOChannel channel = (ISOChannel) f.newInstance(channelName);
			ISOPackager packager;
			if (packagerName != null) {
				packager = (ISOPackager) f.newInstance(packagerName);
				channel.setPackager(packager);
				f.setConfiguration(packager, e);
			}
			QFactory.invoke(channel, "setHeader", e.getAttributeValue("header"));
			f.setLogger(channel, e);
			f.setConfiguration(channel, e);

			if (channel instanceof FilteredChannel) {
				addFilters((FilteredChannel) channel, e, f);
			}
			if (getName() != null)
				channel.setName(getName() + id);
			return channel;
		}

		private void addFilters(FilteredChannel channel, Element e,
				QFactory fact) throws ConfigurationException {
			for (Object o : e.getChildren("filter")) {
				Element f = (Element) o;
				String clazz = f.getAttributeValue("class");
				ISOFilter filter = (ISOFilter) fact.newInstance(clazz);
				fact.setLogger(filter, f);
				fact.setConfiguration(filter, f);
				String direction = f.getAttributeValue("direction");
				if (direction == null)
					channel.addFilter(filter);
				else if ("incoming".equalsIgnoreCase(direction))
					channel.addIncomingFilter(filter);
				else if ("outgoing".equalsIgnoreCase(direction))
					channel.addOutgoingFilter(filter);
				else if ("both".equalsIgnoreCase(direction)) {
					channel.addIncomingFilter(filter);
					channel.addOutgoingFilter(filter);
				}
			}
		}

	}

	public synchronized void setInQueue(String in) {
		String old = this.in;
		this.in = in;
		if (old != null)
			sp.out(old, new Object());

		getPersist().getChild("in").setText(in);
		setModified(true);
	}

	public String getInQueue() {
		return in;
	}

	public synchronized void setOutQueue(String out) {
		this.out = out;
		getPersist().getChild("out").setText(out);
		setModified(true);
	}

	public String getOutQueue() {
		return out;
	}

	public synchronized void setHost(String host) {
		setProperty(getProperties("channel"), "host", host);
		setModified(true);
	}

	public String getHost() {
		return getProperty(getProperties("channel"), "host");
	}

	public synchronized void setPort(int port) {
		setProperty(getProperties("channel"), "port", Integer.toString(port));
		setModified(true);
	}

	public int getPort() {
		int port = 0;
		try {
			port = Integer.parseInt(getProperty(getProperties("channel"),
					"port"));
		} catch (NumberFormatException e) {
			getLog().error(e);
		}
		return port;
	}

	public synchronized void setSocketFactory(String sFac) {
		setProperty(getProperties("channel"), "socketFactory", sFac);
		setModified(true);
	}

	public String getSocketFactory() {
		return getProperty(getProperties("channel"), "socketFactory");
	}

	public synchronized void setErrQueue(String err) {
		this.cnxHandlingQueue = err;
		getPersist().getChild("err").setText(err);
		setModified(true);
	}

	public String getErrQueue() {
		return this.cnxHandlingQueue;
	}

	private boolean isConnectionProcessHandled() {
		return cnxProcessHandling;
	}
}
