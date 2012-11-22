package org.jpos.jposext.oneshotchannelpooldemo.transaction;

import java.io.Serializable;

import org.jpos.core.Configurable;
import org.jpos.core.Configuration;
import org.jpos.core.ConfigurationException;
import org.jpos.iso.ISOException;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.MUX;
import org.jpos.q2.Q2;
import org.jpos.transaction.TransactionConstants;
import org.jpos.transaction.TransactionParticipant;
import org.jpos.util.Log;
import org.jpos.util.LogEvent;
import org.jpos.util.NameRegistrar;

/**
 * @author dgrandemange
 * 
 */
public class DemoParticipant implements TransactionParticipant,
		TransactionConstants, Configurable {

	private String muxRef;

	private static final Log logger = Log.getLog(Q2.LOGGER_NAME,
			DemoParticipant.class.getName());

	public int prepare(long id, Serializable context) {
		LogEvent ev;
		ev = new LogEvent();

		Object obj = NameRegistrar.getIfExists(muxRef);
		if ((null != obj) && (obj instanceof MUX)) {
			try {
				
				ISOMsg req = prepareDummyRequest();
				
				ISOMsg resp = ((MUX) obj).request(req, 5000L);
				
				if (null != resp) {
					ev.addMessage("A response has been received");
					ev.addMessage(resp);
					logger.info(ev);
				} else {
					ev.addMessage("No response received in time");
					logger.warn(ev);
				}
				
			} catch (ISOException e) {
				ev.addMessage(e);
				logger.error(ev);
			}
		} else {
			ev.addMessage(String
					.format("'%s' does not reference a registered MUX compatible instance",
							muxRef));
			logger.warn(ev);
		}

		return PREPARED | NO_JOIN;
	}

	protected ISOMsg prepareDummyRequest() throws ISOException {
		ISOMsg m = new ISOMsg();
		m.setMTI("0100");
		m.set(2, "1234123412341200");
		m.set(3, "000000");
		m.set(4, "000000000100");
		m.set(11, "123456");
		m.recalcBitMap();
		return m;
	}

	public void commit(long id, Serializable context) {
	}

	public void abort(long id, Serializable context) {
	}

	public void setConfiguration(Configuration cfg)
			throws ConfigurationException {
		muxRef = cfg.get("mux-ref");
	}

}
