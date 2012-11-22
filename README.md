OneShotChannelPool-demo
=======================

Demo project of a OneShotChannelPool component for jPos

Running the project demo :
--------------------------
First :
> mvn -Pdemo install

Then, under runtime directory :
> mkdir log

> java -jar q2.jar

The 'src/demo/java/org/jpos/jposext/oneshotchannelpooldemo/transaction/DemoParticipant.java' try to send a dummy message every 5 seconds to the channel pool.
Channel pool is currently configured to work with two channels in a primary-secondary mode.
You may play with the 2 dummy servers configurations in 'deploy' directory to see how channel pool reacts : 
- change servers ports,
- undeploy servers configs
