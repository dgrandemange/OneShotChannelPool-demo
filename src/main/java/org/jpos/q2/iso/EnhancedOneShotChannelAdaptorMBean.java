package org.jpos.q2.iso;

public interface EnhancedOneShotChannelAdaptorMBean extends
		org.jpos.q2.QBeanSupportMBean {

	void setInQueue(java.lang.String in);

	java.lang.String getInQueue();

	void setOutQueue(java.lang.String out);

	java.lang.String getOutQueue();

	void setHost(java.lang.String host);

	java.lang.String getHost();

	void setPort(int port);

	int getPort();

	void setSocketFactory(java.lang.String sFac);

	java.lang.String getSocketFactory();

}
