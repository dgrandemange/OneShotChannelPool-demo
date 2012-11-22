package org.jpos.jposext.oneshotchannelpooldemo;

import org.jpos.space.Space;
import org.jpos.space.SpaceFactory;
import org.jpos.transaction.Context;

@SuppressWarnings("unchecked")
class DemoThread implements Runnable {
	private DemoQBean parent;

	public DemoThread(DemoQBean parent) {
		this.parent = parent;
	}

	@SuppressWarnings("rawtypes")
	public void run() {
		try {
			while (getParent().running()) {
				Thread.sleep(5000);
				Space sp = SpaceFactory.getSpace("tspace:default");
				Context ctx = new Context();
				sp.out("myTxQueue", ctx, 10000);
			}
		} catch (InterruptedException e) {
		}
	}

	protected synchronized DemoQBean getParent() {
		return parent;
	}
}
