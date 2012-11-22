package org.jpos.jposext.oneshotchannelpooldemo;

import org.jpos.q2.QBeanSupport;
import org.jpos.util.NameRegistrar;

/**
 * @author dgrandemange
 * 
 */
public class DemoQBean extends QBeanSupport {

	@Override
	protected void startService() throws Exception {
		new Thread(new DemoThread(this)).start();
		
		NameRegistrar.register(getName(), this);
	}

	@Override
	protected void stopService() throws Exception {
		NameRegistrar.unregister(getName());
	}

}
