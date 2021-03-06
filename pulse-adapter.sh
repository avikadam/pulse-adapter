#!/bin/sh

JAVA=/usr/java/jdk1.8.0/bin/java

# Hard fail support
ENABLEASSERTIONS=-enableassertions
ENABLEASSERTIONS=

PULSE=target/classes
PULSE=target/pulse-adapter-1.1-SNAPSHOT.jar

# Apache Commons command-line-processor
COMMONSCLI=commons-cli-1.2.jar

# Google libraries for JSON serialisation and ISO-8601 time
GUAVA=guava-17.0.jar
GSON=gson-2.2.4.jar
JODA=joda-time-2.4.jar

# Apache Log4j2 and friends
LOG4J2=log4j-api-2.0.jar:log4j-core-2.0.jar
# per http://logging.apache.org/log4j/2.x/faq.html#which_jars
JAVAUTILLOGGINGAPI=jul-to-slf4j-1.7.7.jar
SLF4JAPI=slf4j-api-1.7.7.jar
SLF4JBINDING=log4j-slf4j-impl-2.0.jar
LOG4J2=$LOG4J2:$SLF4JBINDING:$SLF4JAPI:$JAVAUTILLOGGINGAPI

# Asynchronous logging, requires multiple cores.
DISRUPTOR=disruptor-3.2.1.jar
LOG4J2=$LOG4J2:$DISRUPTOR
ENABLEDISRUPTOR=-DLog4jContextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector
##ENABLEDISRUPTOR=

# Thomson Reuters RFA and ValueAdd libraries
RFA=rfa.jar
##RFA=third_party/rfaj7.6.0.E9.all/Libs/rfa.jar
##RFA=rfa.java6.jar
##RFA=third_party/rfaj8.0.0.L1.all.rrg/Libs/rfa.jar
RFAVALUEADD=ValueAdd_DomainRep.jar
##RFAVALUEADD=ValueAdd_DomainRep.java6.jar
##RFAVALUEADD=third_party/rfaj8.0.0.L1.all.rrg/Libs/ValueAdd_DomainRep.jar

set -x
$JAVA \
	-cp $PULSE:$COMMONSCLI:$GUAVA:$GSON:$JODA:$LOG4J2:$RFA:$RFAVALUEADD \
	-Dlog4j.configurationFile=log4j2.xml \
	$ENABLEASSERTIONS \
	$ENABLEDISRUPTOR \
	com.thomsonreuters.PulseAdapter.PulseAdapter $*

