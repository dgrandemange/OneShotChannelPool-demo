package org.jpos.q2.iso;

public interface EnhancedOneShotChannelAdaptorMBean extends
		org.jpos.q2.QBeanSupportMBean {

	void setHost(java.lang.String host);

	java.lang.String getHost();

	void setPort(int port);

	int getPort();

	void setSocketFactory(java.lang.String sFac);

	java.lang.String getSocketFactory();

	int getCnxSuccessCounter();
	
	int getCnxFailedCounter();
	
	void resetCounters();

	int getChannelPoolMaxActive();
	
	int getChannelPoolMaxIdle();
	
	int getChannelPoolMinIdle();
	
	int getChannelPoolNumActive();
	
	int getChannelPoolNumIdle();
}
