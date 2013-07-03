OneShotChannelPool-demo
=======================

Demo project of a one shot channel selector (src/main/java/org/jpos/q2/iso/OneShotChannelPool.java) component for jPos. This component requires a dedicated implementation of jPos one shot channel adaptor current implementation (src/main/java/org/jpos/q2/iso/EnhancedOneShotChannelAdaptor.java).

Running the demo :
------------------
First :
> mvn -Pdemo install

Then, under runtime directory :
> java -jar q2.jar

The 'src/demo/java/org/jpos/jposext/oneshotchannelpooldemo/transaction/DemoParticipant.java' try to send a dummy message request every 5 seconds.

According to transaction manager config (cf. "deploy/20_txmgr.xml"), the dummy request may be routed to different places : 

	<participant class="org.jpos.jposext.oneshotchannelpooldemo.transaction.DemoParticipant">
		
		<!-- 
			As for property "mux-ref", we can choose between : 
			* a one shot channel selector (providing failover/round robin policy)
			* a simple QMUX
		-->
		
		<property name="mux-ref" value="channel-pool.a-channel-selector" />		
		<!-- 		<property name="mux-ref" value="mux.primary-mux" /> -->
		<!-- 		<property name="mux-ref" value="mux.secondary-mux" /> -->
	</participant>  

When "mux-ref" points to a channel selector (ie. "channel-pool.a-channel-selector" configured under "deploy/45_channel_selector.xml"), actual behavior is to work with primary and secondary one shot channels in a primary-secondary mode.
You may play with the server configurations "deploy/10_mock_server_no1.xml" and "deploy/10_mock_server_no2.xml" to see how channel selector reacts : 
- change servers ports,
- undeploy servers configs

You can also make "mux-ref" points to a conventional qmux (like "deploy/45_primary_mux.xml" or "deploy/45_secondary_mux.xml"). Doing so, there is no more failover policy at all. 
