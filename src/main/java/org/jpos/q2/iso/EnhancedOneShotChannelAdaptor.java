package org.jpos.q2.iso;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.pool.BasePoolableObjectFactory;
import org.apache.commons.pool.impl.GenericObjectPool;
import org.apache.commons.pool.impl.GenericObjectPool.Config;
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
import org.jpos.q2.iso.exception.ConnectionFailureException;
import org.jpos.space.Space;
import org.jpos.space.SpaceFactory;
import org.jpos.util.Log;
import org.jpos.util.LogSource;
import org.jpos.util.NameRegistrar;

/**
 * Inspired from jpos original {@link org.jpos.q2.iso.OneShotChannelAdaptor}<BR>
 * 
 * New optional configuration element {@code <cnx-process-handling>} (default is
 * {@code false}) indicates if connection process should be handled so that,
 * when a connection attempt fails, a
 * {@code org.jpos.q2.iso.exception.ConnectionFailureException} is thrown to the
 * client. Note that this exception is a RuntimeException and may be thrown by
 * the {@code receive(...)} methods.<br>
 * 
 * @author dgrandemange
 * 
 */
public class EnhancedOneShotChannelAdaptor extends QBeanSupport implements
		EnhancedOneShotChannelAdaptorMBean, Channel {
	Space<String, Object> sp;
	String in, out;
	long delay;
	boolean cnxProcessHandling;
	int maxConnections;
	int maxConnectAttempts;
	AtomicInteger cnxSuccessCounter;
	AtomicInteger cnxFailedCounter;
	private GenericObjectPool<ISOChannel> channelPool;
	private ExecutorService executorSrv;
	private Map<Thread, Future<ProcessRequestResult>> mapFutures = new HashMap<Thread, Future<ProcessRequestResult>>();
	private Config channelPoolConfig;

	protected class ISOChannelPoolFactory extends
			BasePoolableObjectFactory<ISOChannel> {

		private Element persist;
		private QFactory factory;
		private String name;
		private String socketFactory;
		private Log log;

		public ISOChannelPoolFactory(QFactory factory, Element persist,
				String name, String socketFactory, Log log) {
			this.factory = factory;
			this.persist = persist;
			this.name = name;
			this.socketFactory = socketFactory;
			this.log = log;
		}

		@Override
		public ISOChannel makeObject() throws Exception {
			ISOChannel channel = initChannel();
			getLog().debug(
					String.format(
							"channel '%s' initialized in pool factory makeObject()",
							channel.getName()));
			return channel;
		}

		@Override
		public void destroyObject(ISOChannel channel) throws Exception {
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

		private ISOChannel initChannel() throws ConfigurationException {
			ISOChannel channel;

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

			return channel;
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
				getLog().debug(
						String.format(
								"package '%s' created in pool factory newChannel()",
								packagerName));
				channel.setPackager(packager);
				f.setConfiguration(packager, e);
			}
			QFactory.invoke(channel, "setHeader", e.getAttributeValue("header"));
			f.setLogger(channel, e);
			f.setConfiguration(channel, e);

			if (channel instanceof FilteredChannel) {
				addFilters((FilteredChannel) channel, e, f);
			}

			return channel;
		}

		public Element getPersist() {
			return persist;
		}

		public QFactory getFactory() {
			return factory;
		}

		public String getName() {
			return name;
		}

		public String getSocketFactory() {
			return socketFactory;
		}

	}

	protected class ProcessRequestResult {
		private ISOMsg request;
		private ISOMsg response;
		private Exception exception;

		public ProcessRequestResult(ISOMsg request) {
			super();
			this.request = request;
		}

		public ISOMsg getRequest() {
			return request;
		}

		public void setRequest(ISOMsg request) {
			this.request = request;
		}

		public ISOMsg getResponse() {
			return response;
		}

		public void setResponse(ISOMsg response) {
			this.response = response;
		}

		public Exception getException() {
			return exception;
		}

		public void setException(Exception exception) {
			this.exception = exception;
		}

	}

	public EnhancedOneShotChannelAdaptor() {
		super();
	}

	@SuppressWarnings("unchecked")
	private Space<String, Object> grabSpace(Element e) {
		return (Space<String, Object>) SpaceFactory.getSpace(e != null ? e
				.getText() : "");
	}

	public void initAdaptor() {
		cnxSuccessCounter = new AtomicInteger(0);
		cnxFailedCounter = new AtomicInteger(0);

		Element persist = getPersist();

		sp = grabSpace(persist.getChild("space"));
		in = persist.getChildTextTrim("in");
		out = persist.getChildTextTrim("out");
		delay = 5000;

		String sCnxProcessHandling = persist
				.getChildTextTrim("cnx-process-handling");
		if (null != sCnxProcessHandling) {
			cnxProcessHandling = Boolean.parseBoolean(sCnxProcessHandling);
		} else {
			cnxProcessHandling = false;
		}

		String s = persist.getChildTextTrim("max-connections");
		maxConnections = (s != null) ? Integer.parseInt(s) : 1; // reasonable
																// default
		s = persist.getChildTextTrim("max-connect-attempts");
		maxConnectAttempts = (s != null) ? Integer.parseInt(s) : 15; // reasonable
		// default
		channelPoolConfig = new Config();
		initChannelPoolConfiguration(channelPoolConfig, maxConnections, persist);
	}

	public void startService() throws Exception {
		String step = "start service";

		try {
			initAdaptor();

			ISOChannelPoolFactory poolFactory = new ISOChannelPoolFactory(
					getFactory(), getPersist(), getName(), getSocketFactory(),
					log) {

				@Override
				public void passivateObject(ISOChannel obj) {
					if (null == obj) {
						return;
					}

					if (obj instanceof ISOChannel) {
						ISOChannel channel = (ISOChannel) obj;
						try {
							channel.disconnect();
						} catch (IOException e) {
							getLog().warn(
									String.format(
											"%s : error while attempting to passivate channel '%s' in channel pool. %s",
											this.getName(), channel.getName(),
											e.getMessage()));
						}
					}
				}

			};

			channelPool = new GenericObjectPool<ISOChannel>(poolFactory,
					channelPoolConfig);

			executorSrv = Executors.newCachedThreadPool();
			executorSrv
					.submit(new PollForRequestsInSpaceTask(this, channelPool));

			NameRegistrar.register(getName(), this);
		} catch (Exception e) {
			getLog().error(
					String.format("%s : [%s] an error ocurred. %s",
							this.getName(), step, e.getMessage()));

			try {
				closeChannelPool(step);
			} catch (Exception e2) {
				getLog().error(
						String.format(
								"%s : [%s] error while attempting to close channel pool. %s",
								this.getName(), step, e.getMessage()));
			}

			try {
				shutdownExecutorService(step);
			} catch (Exception e2) {
				getLog().error(
						String.format(
								"%s : [%s] error while attempting to shutdown executor service. %s",
								this.getName(), step, e.getMessage()));
			}

			throw e;
		}
	}

	public void stopService() throws Exception {
		String step = "stop service";
		try {
			try {
				closeChannelPool(step);
			} catch (Exception e2) {
				getLog().error(
						String.format(
								"%s : [%s] error while attempting to close channel pool. %s",
								this.getName(), step, e2.getMessage()));
			}

			try {
				shutdownExecutorService(step);
			} catch (Exception e2) {
				getLog().error(
						String.format(
								"%s : [%s] error while attempting to shutdown executor service. %s",
								this.getName(), step, e2.getMessage()));
			}
		} finally {
			NameRegistrar.unregister(getName());
			NameRegistrar.unregister("channel." + getName());
		}

	}

	protected void initChannelPoolConfiguration(Config poolConfig,
			int _maxConnections, Element persist) {
		poolConfig.maxActive = _maxConnections;

		BigDecimal maxConnectionsBigD = new BigDecimal(_maxConnections);
		poolConfig.maxIdle = maxConnectionsBigD.divide(new BigDecimal("2"),
				BigDecimal.ROUND_UP).intValue();
		poolConfig.minIdle = maxConnectionsBigD.divide(new BigDecimal("4"),
				BigDecimal.ROUND_UP).intValue();

		poolConfig.minEvictableIdleTimeMillis = 120000;
		poolConfig.numTestsPerEvictionRun = _maxConnections;
		poolConfig.timeBetweenEvictionRunsMillis = 60000;

		poolConfig.whenExhaustedAction = GenericObjectPool.WHEN_EXHAUSTED_FAIL;

		poolConfig.testOnBorrow = false;
		poolConfig.testOnReturn = false;
		poolConfig.testWhileIdle = false;
	}

	protected void closeChannelPool(String step) throws Exception {
		if (channelPool != null) {
			channelPool.close();
			channelPool = null;
		}
	}

	protected void shutdownExecutorService(String step) throws Exception {
		if (executorSrv != null) {
			executorSrv.shutdownNow();

			boolean awaitTermination = executorSrv.awaitTermination(30,
					TimeUnit.SECONDS);

			if (!awaitTermination) {
				getLog().error(
						String.format(
								"%s : [%s] error while attempting to shutdown executor service. Cause : timeout while waiting for executor service termination",
								this.getName(), step));
				throw new Exception(
						String.format(
								"%s : error while attempting to shutdown executor service. Cause : timeout while waiting for executor service termination",
								this.getName()));
			} else {
				executorSrv = null;
			}
		}
	}

	/**
	 * Queue a message to be transmitted by this adaptor
	 * 
	 * @param m
	 *            message to send
	 */
	public void send(ISOMsg m) {
		send(m, -1);
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
		mapFutures.put(Thread.currentThread(), null);
		ProcessRequestTask task = new ProcessRequestTask(this, channelPool, m,
				isConnectionProcessHandled());
		Future<ProcessRequestResult> future = executorSrv.submit(task);
		mapFutures.put(Thread.currentThread(), future);
		Thread.yield();
	}

	/**
	 * Receive message
	 */
	public ISOMsg receive() {
		return receive(-1);
	}

	/**
	 * Receive message
	 * 
	 * @param timeout
	 *            time to wait for an incoming message
	 */
	public ISOMsg receive(long timeout) {
		ProcessRequestResult conversationResult;
		Future<ProcessRequestResult> future = mapFutures.get(Thread
				.currentThread());

		if (future == null) {
			getLog().error(
					String.format(
							"%s : cannot retrieve task future for thread '%s'",
							this.getName(), Thread.currentThread()));
			return null;
		}

		try {
			if (timeout >= 0L) {
				conversationResult = future.get(timeout, TimeUnit.MILLISECONDS);
			} else {
				conversationResult = future.get();
			}
		} catch (TimeoutException e) {
			future.cancel(true);
			getLog().warn(
					String.format(
							"%s : timeout while waiting for send/receive task result",
							this.getName()));
			return null;
		} catch (InterruptedException e) {
			getLog().error(
					String.format(
							"%s : exception occurred while waiting for send/receive task result. %s",
							this.getName(), e.getMessage()));
			return null;
		} catch (ExecutionException e) {
			getLog().error(
					String.format(
							"%s : exception occurred while waiting for send/receive task result. %s",
							this.getName(), e.getMessage()));
			return null;
		}

		Exception exception = conversationResult.exception;
		if (exception != null) {
			if (exception instanceof ConnectionFailureException) {
				throw (ConnectionFailureException) exception;
			} else {
				getLog().error(
						String.format(
								"%s : exception occurred in send/receive task. %s",
								this.getName(), exception.getMessage()));
				return null;
			}
		} else {
			return conversationResult.getResponse();
		}
	}

	public class ProcessRequestTask implements Callable<ProcessRequestResult> {
		GenericObjectPool<ISOChannel> channelPool;
		ISOMsg request;
		EnhancedOneShotChannelAdaptor parent;
		private boolean handleConErr;

		public ProcessRequestTask(EnhancedOneShotChannelAdaptor parent,
				GenericObjectPool<ISOChannel> channelPool, ISOMsg request,
				boolean handleConErr) {
			this.parent = parent;
			this.channelPool = channelPool;
			this.request = request;
			this.handleConErr = handleConErr;
		}

		public ProcessRequestResult call() throws Exception {
			ProcessRequestResult convRes = new ProcessRequestResult(request);
			ISOChannel channel = null;
			int[] handbackFields = cfg.getInts("handback-field");

			try {
				try {
					channel = channelPool.borrowObject();
				} catch (NoSuchElementException e) {
					// Pool is exhausted
					getLog().warn(
							String.format(
									"%s : cannot borrow channel from channel pool. Pool is exhausted (max active configured=%d)",
									parent.getName(),
									channelPool.getMaxActive()));
				} catch (Exception e) {
					getLog().error(
							String.format(
									"%s : cannot borrow channel from channel pool. %s",
									parent.getName()), e.getMessage());
				}

				if (channel != null) {

					ISOMsg handBack = null;
					if (handbackFields.length > 0)
						handBack = (ISOMsg) request.clone(handbackFields);

					for (int i = 0; !channel.isConnected()
							&& i < maxConnectAttempts; i++) {
						try {
							channel.reconnect();
							if (!channel.isConnected()) {
								ISOUtil.sleep(100L);
							}
						} catch (IOException e) {
							cnxFailedCounter.incrementAndGet();
							if (this.handleConErr) {
								convRes.setException(new ConnectionFailureException(
										e));
							}
							throw e;
						}
					}
					if (channel.isConnected()) {
						cnxSuccessCounter.incrementAndGet();

						channel.send(request);
						Thread.yield();
						ISOMsg response = channel.receive();
						convRes.setResponse(response);
						channel.disconnect();

						if (handBack != null) {
							response.merge(handBack);
						}
					} else {
						cnxFailedCounter.incrementAndGet();
						if (this.handleConErr) {
							convRes.setException(new ConnectionFailureException());
						}
					}
				}
			} catch (Exception e) {
				if (channel != null) {
					getLog().warn(
							String.format("%s : %s", parent.getName(),
									e.getMessage()));
				} else {
					getLog().warn(
							String.format("%s : %s", parent.getName(),
									e.getMessage()));
				}
			} finally {
				if (channel != null) {
					try {
						channel.disconnect();
					} catch (Exception e) {
						getLog().debug(
								String.format("%s : %s", channel.getName(),
										e.getMessage()));
					}
					channelPool.returnObject(channel);
				}
			}

			return convRes;
		}

	}

	public class PollForRequestsInSpaceTask implements Callable<Void> {
		private EnhancedOneShotChannelAdaptor parent;
		private ExecutorService prstExecutorSrv = new ThreadPoolExecutor(
				maxConnections, Integer.MAX_VALUE, 60, TimeUnit.SECONDS,
				new SynchronousQueue<Runnable>()) {

			protected void afterExecute(Runnable r, Throwable t) {
				super.afterExecute(r, t);

				if (t != null) {
					getLog().warn(
							String.format(
									"%s : an exception has caused execution of ProcessRequestTask to terminate. %s",
									parent.getName(), t.getMessage()));
					return;
				}

				if (r instanceof FutureTask) {
					@SuppressWarnings("unchecked")
					FutureTask<ProcessRequestResult> futureTask = (FutureTask<ProcessRequestResult>) r;

					try {
						ProcessRequestResult cvRes = futureTask.get();

						ISOMsg response = cvRes.getResponse();
						if (response != null) {
							sp.out(out, response);
						}

						Exception exception = cvRes.getException();
						if (exception != null) {
							getLog().warn(
									String.format("%s : %s", parent.getName(),
											exception.getMessage()));
						}
					} catch (InterruptedException e) {
						getLog().warn(
								String.format("%s : %s", parent.getName(),
										e.getMessage()));
					} catch (ExecutionException e) {
						getLog().warn(
								String.format("%s : %s", parent.getName(),
										e.getMessage()));
					}
				}
			}
		};

		public PollForRequestsInSpaceTask(EnhancedOneShotChannelAdaptor parent,
				GenericObjectPool<ISOChannel> channelPool) {
			super();
			this.parent = parent;
		}

		public Void call() {
			while (running()) {
				try {
					Object o = sp.in(in, delay);
					if (o instanceof ISOMsg) {
						ISOMsg request = (ISOMsg) o;
						ProcessRequestTask task = new ProcessRequestTask(
								parent, channelPool, request, false);
						prstExecutorSrv.submit(task);
					}
				} catch (Exception e) {
					getLog().warn(e.getMessage());
					ISOUtil.sleep(1000);
				} finally {
				}
			}

			this.prstExecutorSrv.shutdownNow();
			try {
				boolean awaitTermination = this.prstExecutorSrv
						.awaitTermination(30, TimeUnit.SECONDS);

				if (!awaitTermination) {
					getLog().warn(
							String.format(
									"%s : [PollingTask] error while attempting to shutdown executor service. Cause : timeout while waiting for executor service termination",
									parent.getName()));
				}
			} catch (InterruptedException e) {
			}

			return null;
		}
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
			getLog().error(
					String.format("%s : %s", this.getName(), e.getMessage()));
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

	private boolean isConnectionProcessHandled() {
		return cnxProcessHandling;
	}

	public int getCnxSuccessCounter() {
		return cnxSuccessCounter.get();
	}

	public int getCnxFailedCounter() {
		return cnxFailedCounter.get();
	}

	public void resetCounters() {
		cnxSuccessCounter.set(0);
		cnxFailedCounter.set(0);
	}

	public int getChannelPoolNumActive() {
		if (channelPool == null) {
			return -1;
		}

		return channelPool.getNumActive();
	}

	public int getChannelPoolNumIdle() {
		if (channelPool == null) {
			return -1;
		}

		return channelPool.getNumIdle();
	}

	public int getChannelPoolMaxActive() {
		if (channelPool == null) {
			return -1;
		}

		return channelPool.getMaxActive();
	}

	public int getChannelPoolMaxIdle() {
		if (channelPool == null) {
			return -1;
		}

		return channelPool.getMaxIdle();
	}

	public int getChannelPoolMinIdle() {
		if (channelPool == null) {
			return -1;
		}

		return channelPool.getMinIdle();
	}
}
