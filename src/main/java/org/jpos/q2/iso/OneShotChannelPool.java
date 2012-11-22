package org.jpos.q2.iso;

import java.util.StringTokenizer;

import org.jdom.Element;
import org.jpos.core.ConfigurationException;
import org.jpos.iso.Channel;
import org.jpos.iso.ISOException;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.ISOResponseListener;
import org.jpos.iso.ISOUtil;
import org.jpos.iso.MUX;
import org.jpos.q2.QBeanSupport;
import org.jpos.util.NameRegistrar;
import org.jpos.util.NameRegistrar.NotFoundException;

/**
 * A pool of Channel<BR>
 * Like org.jpos.q2.iso.MUXPool, it provides a load distribution strategy
 * {@code round-robin} and a failover strategy {@code primary-secondary}<BR>
 * Although it's a pool of Channels (not MUXes), it still exposes the MUX
 * interface for convenience purposes.<BR>
 * Channels registration is checked at runtime (i.e. request time, not at
 * service start).<BR>
 * <BR>
 * <U>Typical configuration sample</U><BR>
 * &lt;channel-pool class="org.jpos.q2.iso.OneShotChannelPool" logger="Q2"
 * name="some-channel-pool"&gt;<BR>
 * &nbsp;&nbsp;&lt;channels&gt;primary-channel
 * secondary-channel&lt;/channels&gt;<BR>
 * &nbsp;&nbsp;&lt;strategy&gt;primary-secondary&lt;/strategy&gt;<BR>
 * &nbsp;&nbsp;&lt;!-- &lt;strategy&gt;round-robin&lt;/strategy&gt; --&gt;<BR>
 * &lt;/channel-pool&gt;<BR>
 * 
 * @author dgrandemange
 * 
 */
public class OneShotChannelPool extends QBeanSupport implements MUX {

	private static final String DISTRIBUTION_STRATEGY__ROUND_ROBIN = "round-robin";

	int strategy = 0;
	String[] channelsName;
	int msgno = 0;
	public static final int ROUND_ROBIN = 1;
	public static final int PRIMARY_SECONDARY = 0;

	public void initService() throws ConfigurationException {
		Element e = getPersist();

		channelsName = toStringArray(e.getChildTextTrim("channels"));
		String s = e.getChildTextTrim("strategy");
		strategy = DISTRIBUTION_STRATEGY__ROUND_ROBIN.equals(s) ? ROUND_ROBIN
				: PRIMARY_SECONDARY;

		NameRegistrar.register("channel-pool." + getName(), this);
	}

	public void stopService() {
		NameRegistrar.unregister("channel-pool." + getName());
	}

	private String[] toStringArray(String s) {
		String[] ss = null;
		if (s != null && s.length() > 0) {
			StringTokenizer st = new StringTokenizer(s);
			ss = new String[st.countTokens()];
			for (int i = 0; st.hasMoreTokens(); i++)
				ss[i] = st.nextToken();
		}
		return ss;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.jpos.iso.MUX#request(org.jpos.iso.ISOMsg, long)
	 */
	public ISOMsg request(ISOMsg m, long timeout) throws ISOException {
		int mnumber = 0;
		long maxWait = System.currentTimeMillis() + timeout;
		synchronized (this) {
			mnumber = msgno++;
		}

		Channel selectedChannel = null;
		for (int i = 0; (i < channelsName.length) && (selectedChannel == null)
				&& ((System.currentTimeMillis() < maxWait)); i++) {

			int channelIdx;
			if (PRIMARY_SECONDARY == strategy) {
				channelIdx = i;
			} else {
				int j = (mnumber + i) % channelsName.length;
				channelIdx = j;
			}

			try {
				selectedChannel = findChannelByName(channelsName[channelIdx],
						Channel.class);
				selectedChannel.send(m);
			} catch (NotFoundException e) {
				selectedChannel = null;
			} catch (ConnectionFailureException e) {
				selectedChannel = null;
			}

			if (null == selectedChannel) {
				// TODO Is this delay useful really ?
				ISOUtil.sleep(1000L);
			}
		}

		if (selectedChannel != null) {
			timeout = maxWait - System.currentTimeMillis();
			if (timeout >= 0) {
				ISOMsg received = selectedChannel.receive(timeout);
				return received;
			}
		}

		return null;
	}

	@SuppressWarnings("unchecked")
	protected <T extends Channel> T findChannelByName(String name,
			Class<T> clazz) throws NotFoundException {
		T res = null;

		Object object = NameRegistrar.get(name);
		if (clazz.isAssignableFrom(object.getClass())) {
			res = (T) object;
		} else {
			throw new NotFoundException(name);
		}

		return res;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.jpos.iso.MUX#request(org.jpos.iso.ISOMsg, long,
	 * org.jpos.iso.ISOResponseListener, java.lang.Object)
	 */
	public void request(ISOMsg m, long timeout, ISOResponseListener r,
			Object handBack) throws ISOException {
		// FIXME not yet implemented
		throw new ISOException("not yet implemented");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.jpos.iso.MUX#isConnected()
	 */
	public boolean isConnected() {
		// We consider pool is connected if one channel at least is well
		// deployed and registered

		boolean res = false;

		for (int i = 0; (i < channelsName.length); i++) {
			try {
				findChannelByName(channelsName[i], Channel.class);
				res = true;
				break;
			} catch (NotFoundException e) {
				// Safe to ignore
			}
		}

		return res;
	}

}
