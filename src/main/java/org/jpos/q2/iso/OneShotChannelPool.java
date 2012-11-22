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

/**
 * A channel pool inspired from org.jpos.q2.iso.MUXPool<BR>
 * 
 * @author dgrandemange
 * 
 */
public class OneShotChannelPool extends QBeanSupport implements MUX {

	int strategy = 0;
	String[] channelsName;
	Channel[] channels;
	int msgno = 0;
	public static final int ROUND_ROBIN = 1;
	public static final int PRIMARY_SECONDARY = 0;

	public void initService() throws ConfigurationException {
		Element e = getPersist();

		channelsName = toStringArray(e.getChildTextTrim("channels"));
		String s = e.getChildTextTrim("strategy");
		strategy = "round-robin".equals(s) ? ROUND_ROBIN : PRIMARY_SECONDARY;

		channels = new Channel[channelsName.length];
		try {
			for (int i = 0; i < channels.length; i++)
				channels[i] = getChannelFromName(channelsName[i]);
		} catch (NameRegistrar.NotFoundException ex) {
			throw new ConfigurationException(ex);
		}
		NameRegistrar.register("channel-pool." + getName(), this);
	}

	public void stopService() {
		NameRegistrar.unregister("channel-pool." + getName());
	}

	private Channel getChannelFromName(String name)
			throws NameRegistrar.NotFoundException {
		return (Channel) NameRegistrar.get(name);
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
		for (int i = 0; (i < channels.length) && (selectedChannel == null)
				&& ((System.currentTimeMillis() < maxWait)); i++) {
			if (PRIMARY_SECONDARY == strategy) {
				selectedChannel = sendAttempt(m, channels[i]);
			} else {
				int j = (mnumber + i) % channels.length;

				selectedChannel = sendAttempt(m, channels[j]);
			}
			 
			if (null == selectedChannel) {
				// TODO Is sleeping really necessary ?
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

	private Channel sendAttempt(ISOMsg m, Channel channel) {
		try {
			channel.send(m);
			return channel;
		}

		catch (ConnectionFailureException e) {
			@SuppressWarnings("unused")
			Throwable cause = e.getCause();
			// TODO do something with cause ? Check if IOException instance, ... ?
		}

		return null;
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
		// True by default
		return true;
	}

}
