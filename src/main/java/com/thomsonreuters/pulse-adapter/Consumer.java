/* Simple consumer.
 */

package com.thomsonreuters.PulseAdapter;

import java.util.*;
import java.net.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.joda.time.DateTime;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.reuters.rfa.common.Client;
import com.reuters.rfa.common.Context;
import com.reuters.rfa.common.Event;
import com.reuters.rfa.common.EventQueue;
import com.reuters.rfa.common.EventSource;
import com.reuters.rfa.common.Handle;
import com.reuters.rfa.dictionary.FidDef;
import com.reuters.rfa.dictionary.FieldDictionary;
import com.reuters.rfa.omm.OMMArray;
import com.reuters.rfa.omm.OMMData;
import com.reuters.rfa.omm.OMMDataBuffer;
import com.reuters.rfa.omm.OMMElementEntry;
import com.reuters.rfa.omm.OMMElementList;
import com.reuters.rfa.omm.OMMEncoder;
import com.reuters.rfa.omm.OMMEntry;
import com.reuters.rfa.omm.OMMEnum;
import com.reuters.rfa.omm.OMMFieldEntry;
import com.reuters.rfa.omm.OMMFieldList;
import com.reuters.rfa.omm.OMMFilterEntry;
import com.reuters.rfa.omm.OMMFilterList;
import com.reuters.rfa.omm.OMMIterable;
import com.reuters.rfa.omm.OMMMap;
import com.reuters.rfa.omm.OMMMapEntry;
import com.reuters.rfa.omm.OMMMsg;
import com.reuters.rfa.omm.OMMPool;
import com.reuters.rfa.omm.OMMState;
import com.reuters.rfa.omm.OMMTypes;
import com.reuters.rfa.rdm.RDMInstrument;
import com.reuters.rfa.rdm.RDMMsgTypes;
import com.reuters.rfa.rdm.RDMService;
import com.reuters.rfa.session.DataDictInfo;
import com.reuters.rfa.session.event.ConnectionEvent;
import com.reuters.rfa.session.event.EntitlementsAuthenticationEvent;
import com.reuters.rfa.session.event.MarketDataDictEvent;
import com.reuters.rfa.session.event.MarketDataDictStatus;
import com.reuters.rfa.session.event.MarketDataItemEvent;
import com.reuters.rfa.session.event.MarketDataItemStatus;
import com.reuters.rfa.session.event.MarketDataSvcEvent;
import com.reuters.rfa.session.event.MarketDataSvcStatus;
import com.reuters.rfa.session.Session;
import com.reuters.rfa.session.TimerIntSpec;
import com.reuters.rfa.session.MarketDataDictSub;
import com.reuters.rfa.session.MarketDataEnums;
import com.reuters.rfa.session.MarketDataItemSub;
import com.reuters.rfa.session.MarketDataSubscriber;
import com.reuters.rfa.session.MarketDataSubscriberInterestSpec;
import com.reuters.rfa.session.omm.OMMConnectionEvent;
import com.reuters.rfa.session.omm.OMMConnectionIntSpec;
import com.reuters.rfa.session.omm.OMMConsumer;
import com.reuters.rfa.session.omm.OMMErrorIntSpec;
import com.reuters.rfa.session.omm.OMMItemEvent;
import com.reuters.rfa.session.omm.OMMItemIntSpec;
import com.reuters.tibmsg.TibException;
import com.reuters.tibmsg.TibField;
import com.reuters.tibmsg.TibMsg;
import com.reuters.tibmsg.TibMfeedDict;
import com.thomsonreuters.rfa.valueadd.domainrep.rdm.dictionary.RDMDictionary;
import com.thomsonreuters.rfa.valueadd.domainrep.rdm.dictionary.RDMDictionaryCache;
import com.thomsonreuters.rfa.valueadd.domainrep.rdm.dictionary.RDMDictionaryRequest;
import com.thomsonreuters.rfa.valueadd.domainrep.rdm.dictionary.RDMDictionaryRequestAttrib;
import com.thomsonreuters.rfa.valueadd.domainrep.rdm.dictionary.RDMDictionaryResponse;
import com.thomsonreuters.rfa.valueadd.domainrep.rdm.directory.RDMDirectory;
import com.thomsonreuters.rfa.valueadd.domainrep.rdm.directory.RDMDirectoryRequest;
import com.thomsonreuters.rfa.valueadd.domainrep.rdm.directory.RDMDirectoryRequestAttrib;
import com.thomsonreuters.rfa.valueadd.domainrep.rdm.directory.RDMDirectoryResponse;
import com.thomsonreuters.rfa.valueadd.domainrep.rdm.directory.RDMDirectoryResponsePayload;
import com.thomsonreuters.rfa.valueadd.domainrep.rdm.directory.Service;
import com.thomsonreuters.rfa.valueadd.domainrep.rdm.login.RDMLogin;
import com.thomsonreuters.rfa.valueadd.domainrep.rdm.login.RDMLoginRequest;
import com.thomsonreuters.rfa.valueadd.domainrep.rdm.login.RDMLoginRequestAttrib;
import com.thomsonreuters.rfa.valueadd.domainrep.rdm.login.RDMLoginResponse;
import com.thomsonreuters.rfa.valueadd.domainrep.ResponseStatus;


public class Consumer implements Client {
	private static Logger LOG = LogManager.getLogger (Consumer.class.getName());
	private static final Marker SPS_MARKER = MarkerManager.getMarker ("SPS");

	private SessionConfig config;

/* RFA context. */
	private Rfa rfa;

/* RFA asynchronous event queue. */
	private EventQueue event_queue;

/* RFA session defines one or more connections for horizontal scaling. */
	private Session session;

/* RFA OMM consumer interface. */
	private OMMConsumer omm_consumer;
	private OMMPool omm_pool;
	private OMMEncoder omm_encoder;

/* RFA market data subscriber interface. */
	private MarketDataDictSub market_data_dictionary_subscriber;
	private MarketDataSubscriber market_data_subscriber;
	private TibMsg msg;
	private TibField field;
	private Set<Integer> field_set;

/* JSON serialisation */
	private Gson gson;
	private StringBuilder sb;

/* Data dictionaries. */
	private RDMDictionaryCache rdm_dictionary;

/* Directory */
	private Map<String, ItemStream> directory;

/* RFA Item event consumer */
	private Handle error_handle;
	private Handle login_handle;
	private Handle directory_handle;

/* Resubscription management via timer */
	private Handle resubscription_handle;
	private SubscriptionManager subscription_manager;

	private class FlaggedHandle {
		private Handle handle;
		private boolean flag;

		public FlaggedHandle (Handle handle) {
			this.handle = handle;
			this.flag = false;
		}

		public Handle getHandle() {
			return this.handle;
		}

		public boolean isFlagged() {
			return this.flag;
		}

		public void setFlag() {
			this.flag = true;
		}
	}

	private Map<String, FlaggedHandle> dictionary_handle;
	private ImmutableMap<String, Integer> appendix_a;

/* Reuters Wire Format versions. */
	private byte rwf_major_version;
	private byte rwf_minor_version;

	private boolean is_muted;
	private boolean pending_directory;
	private boolean pending_dictionary;

	private static final boolean UNSUBSCRIBE_ON_SHUTDOWN = false;
	private static final boolean DO_NOT_CACHE_ZERO_VALUE = true;
	private static final boolean DO_NOT_CACHE_BLANK_VALUE = true;

	private static final int OMM_PAYLOAD_SIZE	= 5000;
	private static final int GC_DELAY_MS		= 15000;
	private static final int RESUBSCRIPTION_MS	= 180000;

	private static final String RSSL_PROTOCOL	= "rssl";
	private static final String SSLED_PROTOCOL	= "ssled";

	public Consumer (SessionConfig config, Rfa rfa, EventQueue event_queue) {
		this.config = config;
		this.rfa = rfa;
		this.event_queue = event_queue;
		this.rwf_major_version = 0;
		this.rwf_minor_version = 0;
		this.is_muted = true;
		this.pending_directory = false;
		this.pending_dictionary = false;

		if (this.config.getProtocol().equalsIgnoreCase (RSSL_PROTOCOL))
		{
			this.pending_directory = true;
			this.pending_dictionary = true;
		}
		else if (this.config.getProtocol().equalsIgnoreCase (SSLED_PROTOCOL))
		{
			if (!(this.config.hasFieldDictionary() && this.config.hasEnumDictionary())) {
				LOG.trace ("Requesting Marketfeed dictionary from upstream configured source.");
				this.pending_directory = this.pending_dictionary = true;
			} else {
				LOG.trace ("Reading Marketfeed dictionary from {},{}", this.config.getFieldDictionary(), this.config.getEnumDictionary());
				try {
					TibMsg.ReadMfeedDictionary (this.config.getFieldDictionary(), this.config.getEnumDictionary());
					this.OnMarketDataDictComplete();
				} catch (TibException e) {
					LOG.throwing (e);
				}
			}
		}
	}

	private class SubscriptionManager implements Client {
		private final Consumer consumer;

		public SubscriptionManager (Consumer consumer) {
			this.consumer = consumer;
		}

		@Override
		public void processEvent (Event event) {
			LOG.trace (event);
			switch (event.getType()) {
			case Event.TIMER_EVENT:
				this.OnTimerEvent (event);
				break;

			default:
				LOG.trace ("Uncaught: {}", event);
				break;
			}
		}

/* All requests are throttled per The Session Layer Package Configuration thus
 * no need to perform additional pacing at the application layer.  Default is
 * to permit 200 outstanding requests at a time.  See throttleEnabled, and
 * throttleType for interval based request batching.
 */
		private void OnTimerEvent (Event event) {
			LOG.trace ("Resubscription event ...");
			if (null != this.consumer) {
				this.consumer.resubscribe();
			}
		}
	}

	public void init() throws Exception {
		LOG.trace (this.config);

/* Manual serialisation */
		this.sb = new StringBuilder (512);

/* Null object support */
		this.gson = new GsonBuilder()
				.disableHtmlEscaping()
				.serializeNulls()
				.create();

/* Configuring the session layer package.
 */
		LOG.trace ("Acquiring RFA session.");
		this.session = Session.acquire (this.config.getSessionName());

/* RFA Version Info. The version is only available if an application
 * has acquired a Session (i.e., the Session Layer library is laoded).
 */
		LOG.debug ("RFA: { \"productVersion\": \"{}\" }", Context.getRFAVersionInfo().getProductVersion());

		if (this.config.getProtocol().equalsIgnoreCase (RSSL_PROTOCOL))
		{
/* Initializing an OMM consumer. */
			LOG.trace ("Creating OMM consumer.");
			this.omm_consumer = (OMMConsumer)this.session.createEventSource (EventSource.OMM_CONSUMER,
						this.config.getConsumerName(),
						false /* complete events */);

/* Registering for Events from an OMM Consumer. */
			LOG.trace ("Registering OMM error interest.");
			OMMErrorIntSpec ommErrorIntSpec = new OMMErrorIntSpec();
			this.error_handle = this.omm_consumer.registerClient (this.event_queue, ommErrorIntSpec, this, null);

/* OMM memory management. */
			this.omm_pool = OMMPool.create (OMMPool.SINGLE_THREADED);
			this.omm_encoder = this.omm_pool.acquireEncoder();
			this.omm_encoder.initialize (OMMTypes.MSG, OMM_PAYLOAD_SIZE);

			this.rdm_dictionary = new RDMDictionaryCache();

			this.sendLoginRequest();
			this.sendDirectoryRequest();
		}
		else if (this.config.getProtocol().equalsIgnoreCase (SSLED_PROTOCOL))
		{
/* Initializing a Market Data Subscriber. */
			LOG.trace ("Creating market data subscriber.");
			this.market_data_subscriber = (MarketDataSubscriber)this.session.createEventSource (EventSource.MARKET_DATA_SUBSCRIBER,
						this.config.getConsumerName(),
						false /* complete events */);

			LOG.trace ("Registering market data status interest.");
			MarketDataSubscriberInterestSpec marketDataSubscriberInterestSpec = new MarketDataSubscriberInterestSpec();
			marketDataSubscriberInterestSpec.setMarketDataSvcInterest (true);
			marketDataSubscriberInterestSpec.setConnectionInterest (false);
			marketDataSubscriberInterestSpec.setEntitlementsInterest (false);
			this.error_handle = this.market_data_subscriber.registerClient (this.event_queue, marketDataSubscriberInterestSpec, this, null);

/* Initializing a Market Data Dictionary Subscriber. */
			this.market_data_dictionary_subscriber = new MarketDataDictSub();

/* TibMsg memory management. */
			this.msg = new TibMsg();
			this.field = new TibField();
		}
		else
		{
			throw new Exception ("Unsupported transport protocol \"" + this.config.getProtocol() + "\".");
		}

		this.directory = new LinkedHashMap<String, ItemStream>();
		this.dictionary_handle = new TreeMap<String, FlaggedHandle>();
		this.field_set = Sets.newTreeSet();

/* Resubsription manager */
		if (RESUBSCRIPTION_MS > 0) {
			final TimerIntSpec timer = new TimerIntSpec();
			timer.setDelay (RESUBSCRIPTION_MS);
			timer.setRepeating (true);
			if (this.config.getProtocol().equalsIgnoreCase (SSLED_PROTOCOL)) {
				this.subscription_manager = new SubscriptionManager (this);
				this.resubscription_handle = this.market_data_subscriber.registerClient (this.event_queue, timer, this.subscription_manager, null);
			}
		}
	}

	public void clear() {
		if (null != this.resubscription_handle) {
			this.resubscription_handle = null;
		}
		if (null != this.market_data_subscriber) {
			LOG.trace ("Closing MarketDataSubscriber.");
			if (UNSUBSCRIBE_ON_SHUTDOWN) {
/* 9.9.3 Upstream Batching
 * Market Data Subscriber’s unsubscribeAll() can be used to encourage RFA Java to batch unsubscribe
 * requests on connections that support batching of those requests into a message.
 */
				this.market_data_subscriber.unsubscribeAll();
				if (null != this.directory && !this.directory.isEmpty())
					this.directory.clear();
				if (null != this.error_handle) {
					this.market_data_subscriber.unregisterClient (this.error_handle);
					this.error_handle = null;
				}
			} else {
				if (null != this.directory && !this.directory.isEmpty())
					this.directory.clear();
				if (null != this.error_handle)
					this.error_handle = null;
			}
			this.market_data_subscriber.destroy();
			this.market_data_subscriber = null;
		}
		if (null != this.rdm_dictionary)
			this.rdm_dictionary = null;
		if (null != this.omm_encoder)
			this.omm_encoder = null;
		if (null != this.omm_pool) {
			LOG.trace ("Closing OMMPool.");
			this.omm_pool.destroy();
			this.omm_pool = null;
		}
		if (null != this.omm_consumer) {
			LOG.trace ("Closing OMMConsumer.");
/* 8.2.11 Shutting Down an Application
 * an application may just destroy Event
 * Source, in which case the closing of the streams is handled by the RFA.
 */
			if (UNSUBSCRIBE_ON_SHUTDOWN) {
/* 9.2.5.3 Batch Close
 * The consumer application
 * builds a List of Handles of the event streams to close and calls OMMConsumer.unregisterClient().
 */
				if (null != this.directory && !this.directory.isEmpty()) {
					List<Handle> item_handles = new ArrayList<Handle> (this.directory.size());
					for (ItemStream item_stream : this.directory.values()) {
						if (item_stream.hasItemHandle())
							item_handles.add (item_stream.getItemHandle());
					}
					this.omm_consumer.unregisterClient (item_handles, null);
					this.directory.clear();
				}
				if (null != this.dictionary_handle && !this.dictionary_handle.isEmpty()) {
					for (FlaggedHandle flagged_handle : this.dictionary_handle.values()) {
						this.omm_consumer.unregisterClient (flagged_handle.getHandle());
					}
					this.dictionary_handle.clear();
				}
				if (null != this.directory_handle) {
					this.omm_consumer.unregisterClient (this.directory_handle);
					this.directory_handle = null;
				}
				if (null != this.login_handle) {
					this.omm_consumer.unregisterClient (this.login_handle);
					this.login_handle = null;
				}
			} else {
				if (null != this.directory && !this.directory.isEmpty())
					this.directory.clear();
				if (null != this.dictionary_handle && !this.dictionary_handle.isEmpty())
					this.dictionary_handle.clear();
				if (null != this.directory_handle)
					this.directory_handle = null;
				if (null != this.login_handle)
					this.login_handle = null;
			}
			this.omm_consumer.destroy();
			this.omm_consumer = null;
		}
		if (null != this.session) {
			LOG.trace ("Closing RFA Session.");
			this.session.release();
			this.session = null;
		}
	}

/* Create an item stream for a given symbol name.  The Item Stream maintains
 * the provider state on behalf of the application.
 */
	public void createItemStream (Instrument instrument, ItemStream item_stream) {
/* Construct directory unique key */
		this.sb.setLength (0);
		this.sb	.append (instrument.getService())
			.append ('.')
			.append (instrument.getName());
		this.createItemStream (instrument, item_stream, this.sb.toString());
	}

	public void createItemStream (Instrument instrument, ItemStream item_stream, String key) {
		LOG.trace ("Creating item stream for RIC \"{}\" on service \"{}\".", instrument.getName(), instrument.getService());
		item_stream.setItemName (instrument.getName());
		item_stream.setServiceName (instrument.getService());
/* viewType:- RDMUser.View.FIELD_ID_LIST or RDMUser.View.ELEMENT_NAME_LIST */
		final ImmutableSortedSet<String> view_by_name = ImmutableSortedSet.copyOf (instrument.getFields());
		item_stream.setViewByName (view_by_name);

		if (!this.pending_dictionary) {
			item_stream.setViewByFid (this.createViewByFid (item_stream.getViewByName()));
			if (this.config.getProtocol().equalsIgnoreCase (RSSL_PROTOCOL)) {
				this.sendItemRequest (item_stream);
			}
			else if (this.config.getProtocol().equalsIgnoreCase (SSLED_PROTOCOL)) {
				this.addSubscription (item_stream);
			}
		}
		this.directory.put (key, item_stream);
		LOG.trace ("Directory size: {}", this.directory.size());
	}

	public void destroyItemStream (ItemStream item_stream) {
/* Construct directory unique key */
		this.sb.setLength (0);
		this.sb .append (item_stream.getServiceName())
			.append ('.')
			.append (item_stream.getItemName());
		this.destroyItemStream (item_stream, this.sb.toString());
	}

	public void destroyItemStream (ItemStream item_stream, String key) {
		LOG.trace ("Destroying item stream for RIC \"{}\" on service \"{}\".", item_stream.getItemName(), item_stream.getServiceName());
		if (this.config.getProtocol().equalsIgnoreCase (RSSL_PROTOCOL)) {
			this.cancelItemRequest (item_stream);
		}
		else if (this.config.getProtocol().equalsIgnoreCase (SSLED_PROTOCOL)) {
			this.removeSubscription (item_stream);
		}
		this.directory.remove (key);
		LOG.trace ("Directory size: {}", this.directory.size());
	}

/* Create a basic immutable map of MarketFeed FID names to FID values */
	private ImmutableMap<String, Integer> createDictionaryMap() {
		final Map<String, Integer> map = Maps.newLinkedHashMap();
		if (TibMsg.GetMfeedDictNumFids() > 0)
		{
			final TibMfeedDict mfeed_dictionary[] = TibMsg.GetMfeedDictionary();
			for (int i = 0; i < mfeed_dictionary.length; i++) {
				if (null == mfeed_dictionary[i]) continue;
				final int fid = (i > TibMsg.GetMfeedDictPosFids()) ? (TibMsg.GetMfeedDictPosFids() - i) : i;
				map.put (mfeed_dictionary[i].fname, Integer.valueOf (fid));
			}
		}
		return ImmutableMap.copyOf (map);
	}

/* Convert a view by FID name to a view by FID values */
	private ImmutableSortedSet<Integer> createViewByFid (ImmutableSortedSet<String> view_by_name) {
		final ArrayList<Integer> fid_list = new ArrayList<Integer> (view_by_name.size());
		for (String name : view_by_name) {
			final Integer fid = this.appendix_a.get (name);
			if (null == fid) {
				LOG.error ("Field \"{}\" not described in appendix_a dictionary.", name);
			} else {
				LOG.trace ("{} -> {}", name, fid);
				fid_list.add (fid);
			}
		}
		final Integer[] fid_array = fid_list.toArray (new Integer [fid_list.size()]);
		return ImmutableSortedSet.copyOf (fid_array);
	}

	public void resubscribe() {
/* Cannot decode responses so do not allow wire subscriptions until dictionary is present */
		if (this.pending_dictionary)
			return;
		if (this.config.getProtocol().equalsIgnoreCase (RSSL_PROTOCOL))
		{
			if (null == this.omm_consumer) {
				LOG.warn ("Resubscribe whilst consumer is invalid.");
				return;
			}

			for (ItemStream item_stream : this.directory.values()) {
				if (!item_stream.hasViewByFid()) {
					item_stream.setViewByFid (this.createViewByFid (item_stream.getViewByName()));
				}
				if (!item_stream.hasItemHandle()) {
					this.sendItemRequest (item_stream);
				}
			}
		}
		else if (this.config.getProtocol().equalsIgnoreCase (SSLED_PROTOCOL))
		{
			if (null == this.market_data_subscriber) {
				LOG.warn ("Resubscribe whilst subscriber is invalid.");
				return;
			}

/* foreach directory item stream */
			for (ItemStream item_stream : this.directory.values()) {
				if (!item_stream.hasViewByFid()) {
					item_stream.setViewByFid (this.createViewByFid (item_stream.getViewByName()));
				}
				if (!item_stream.hasItemHandle()) {
					this.addSubscription (item_stream);
				}
			}
		}
	}

	private void sendItemRequest (ItemStream item_stream) {
		LOG.trace ("Sending market price request.");
		OMMMsg msg = this.omm_pool.acquireMsg();
		msg.setMsgType (OMMMsg.MsgType.REQUEST);
//		msg.setMsgModelType (RDMMsgTypes.MARKET_PRICE);
		msg.setMsgModelType ((short)11 /* RDMMsgTypes.SPS */);
		msg.setAssociatedMetaInfo (this.login_handle);
		msg.setIndicationFlags (OMMMsg.Indication.REFRESH);
		msg.setAttribInfo (item_stream.getServiceName(), item_stream.getItemName(), RDMInstrument.NameType.RIC);

//		LOG.trace ("Registering OMM item interest for MMT_MARKET_PRICE/{}/{}", item_stream.getServiceName(), item_stream.getItemName());
		LOG.trace ("Registering OMM item interest for MMT_SPS/{}/{}", item_stream.getServiceName(), item_stream.getItemName());
		OMMItemIntSpec ommItemIntSpec = new OMMItemIntSpec();
		ommItemIntSpec.setMsg (msg);
		item_stream.setItemHandle (this.omm_consumer.registerClient (this.event_queue, ommItemIntSpec, this, item_stream));
		this.omm_pool.releaseMsg (msg);
	}

/* 8.2.11.1 Unregistering Interest In OMM Market Information
 * if the event Stream had already been closed by RFA ... the application does not need to not call
 * unregisterClient().
 */
	private void cancelItemRequest (ItemStream item_stream) {
		if (item_stream.hasItemHandle()) {
			LOG.trace ("Cancelling market price request.");
			this.omm_consumer.unregisterClient (item_stream.getItemHandle());
		} else {
			LOG.trace ("Market price request closed by RFA.");
		}
	}

	private void addSubscription (ItemStream item_stream) {
		MarketDataItemSub marketDataItemSub = new MarketDataItemSub();
		marketDataItemSub.setServiceName (item_stream.getServiceName());
		marketDataItemSub.setItemName (item_stream.getItemName());
		LOG.trace ("Adding market data subscription.");
		item_stream.setItemHandle (this.market_data_subscriber.subscribe (this.event_queue, marketDataItemSub, this, item_stream));
	}

	private void removeSubscription (ItemStream item_stream) {
		if (item_stream.hasItemHandle()) {
			LOG.trace ("Removing market data subscription.");
			this.market_data_subscriber.unsubscribe (item_stream.getItemHandle());
		} else {
			LOG.trace ("Market data subscription closed by RFA.");
		}
	}

/* Making a Login Request
 * A Login request message is encoded and sent by OMM Consumer and OMM non-
 * interactive provider applications.
 */
	private void sendLoginRequest() throws UnknownHostException {
		LOG.trace ("Sending login request.");
		RDMLoginRequest request = new RDMLoginRequest();
		RDMLoginRequestAttrib attribInfo = new RDMLoginRequestAttrib();

/* RFA/Java only.
 */
		request.setMessageType (RDMLoginRequest.MessageType.REQUEST);
		request.setIndicationMask (EnumSet.of (RDMLoginRequest.IndicationMask.REFRESH));
		attribInfo.setRole (RDMLogin.Role.CONSUMER);

/* DACS username (required).
 */
		attribInfo.setNameType (RDMLogin.NameType.USER_NAME);
		attribInfo.setName (this.config.hasUserName() ?
			this.config.getUserName()
			: System.getProperty ("user.name"));

/* DACS Application Id (optional).
 * e.g. "256"
 */
		if (this.config.hasApplicationId())
			attribInfo.setApplicationId (this.config.getApplicationId());

/* DACS Position name (optional).
 * e.g. "127.0.0.1/net"
 */
		if (this.config.hasPosition()) {
			if (!this.config.getPosition().isEmpty())
				attribInfo.setPosition (this.config.getPosition());
		} else {
			this.sb.setLength (0);
			this.sb .append (InetAddress.getLocalHost().getHostAddress())
				.append ('/')
				.append (InetAddress.getLocalHost().getHostName());
			attribInfo.setPosition (this.sb.toString());
		}

/* Instance Id (optional).
 * e.g. "<Instance Id>"
 */
		if (this.config.hasInstanceId())
			attribInfo.setInstanceId (this.config.getInstanceId());

		request.setAttrib (attribInfo);

		LOG.trace ("Registering OMM item interest for MMT_LOGIN");
		OMMMsg msg = request.getMsg (this.omm_pool);
GenericOMMParser.parse (msg);
		OMMItemIntSpec ommItemIntSpec = new OMMItemIntSpec();
		ommItemIntSpec.setMsg (msg);
		this.login_handle = this.omm_consumer.registerClient (this.event_queue, ommItemIntSpec, this, null);

/* Reset status */
		this.pending_directory = true;
// Maintain current status of dictionary instead of interrupting existing consumers.
//		this.pending_dictionary = true;
	}

/* Make a directory request to see if we can ask for a dictionary.
 */
	private void sendDirectoryRequest() {
		LOG.trace ("Sending directory request.");
		RDMDirectoryRequest request = new RDMDirectoryRequest();
		RDMDirectoryRequestAttrib attribInfo = new RDMDirectoryRequestAttrib();

/* RFA/Java only.
 */
		request.setMessageType (RDMDirectoryRequest.MessageType.REQUEST);
		request.setIndicationMask (EnumSet.of (RDMDirectoryRequest.IndicationMask.REFRESH));

/* Limit to named service */
		if (this.config.hasServiceName())
			attribInfo.setServiceName (this.config.getServiceName());

/* Request INFO and STATE filters for service names and states */
		attribInfo.setFilterMask (EnumSet.of (RDMDirectory.FilterMask.INFO, RDMDirectory.FilterMask.STATE));

		request.setAttrib (attribInfo);

		LOG.trace ("Registering OMM item interest for MMT_DIRECTORY");
		OMMMsg msg = request.getMsg (this.omm_pool);
		OMMItemIntSpec ommItemIntSpec = new OMMItemIntSpec();
		ommItemIntSpec.setMsg (msg);
		this.directory_handle = this.omm_consumer.registerClient (this.event_queue, ommItemIntSpec, this, null);
	}

/* Make a dictionary request.
 *
 * 5.8.3 Version Check
 * Dictionary version checking can be performed by the client after a refresh
 * (Section 2.2) response message of a Dictionary is received.
 */
	private void sendDictionaryRequest (String service_name, String dictionary_name) {
		LOG.trace ("Sending dictionary request for \"{}\" from service \"{}\".", dictionary_name, service_name);
		RDMDictionaryRequest request = new RDMDictionaryRequest();
		RDMDictionaryRequestAttrib attribInfo = new RDMDictionaryRequestAttrib();

/* RFA/Java only.
 */
		request.setMessageType (RDMDictionaryRequest.MessageType.REQUEST);
		request.setIndicationMask (EnumSet.of (RDMDictionaryRequest.IndicationMask.REFRESH));

// RDMDictionary.Filter.NORMAL=0x7: Provides all information needed for decoding
		attribInfo.setVerbosity (RDMDictionary.Verbosity.NORMAL);
		attribInfo.setServiceName (service_name);
		attribInfo.setDictionaryName (dictionary_name);

		request.setAttrib (attribInfo);

		LOG.trace ("Registering OMM item interest for MMT_DICTIONARY/{}/{}", service_name, dictionary_name);
		OMMMsg msg = request.getMsg (this.omm_pool);
		OMMItemIntSpec ommItemIntSpec = new OMMItemIntSpec();
		ommItemIntSpec.setMsg (msg);
		this.dictionary_handle.put (dictionary_name,
			new FlaggedHandle (this.omm_consumer.registerClient (this.event_queue, ommItemIntSpec, this, dictionary_name /* closure */)));
	}

	private void addDictionarySubscription (DataDictInfo dictionary_info) {
		LOG.trace ("Sending dictionary request for \"{}\".", dictionary_info.getDictType().toString());
		this.market_data_dictionary_subscriber.setDataDictInfo (dictionary_info);
		this.dictionary_handle.put (dictionary_info.getDictType().toString(),
			new FlaggedHandle (this.market_data_subscriber.subscribe (this.event_queue, this.market_data_dictionary_subscriber, this, dictionary_info.getDictType().toString() /* closure */)));
	}

	@Override
	public void processEvent (Event event) {
		LOG.trace (event);
		switch (event.getType()) {
		case Event.OMM_ITEM_EVENT:
			this.OnOMMItemEvent ((OMMItemEvent)event);
			break;

		case Event.OMM_CONNECTION_EVENT:
			this.OnConnectionEvent ((OMMConnectionEvent)event);
			break;

		case Event.MARKET_DATA_ITEM_EVENT:
			this.OnMarketDataItemEvent ((MarketDataItemEvent)event);
			break;

		case Event.MARKET_DATA_SVC_EVENT:
			this.OnMarketDataSvcEvent ((MarketDataSvcEvent)event);
			break;

		case Event.MARKET_DATA_DICT_EVENT:
			this.OnMarketDataDictEvent ((MarketDataDictEvent)event);
			break;

		case Event.CONNECTION_EVENT:
			this.OnConnectionEvent ((ConnectionEvent)event);
			break;

		case Event.ENTITLEMENTS_AUTHENTICATION_EVENT:
			this.OnEntitlementsAuthenticationEvent ((EntitlementsAuthenticationEvent)event);
			break;

		default:
			LOG.trace ("Uncaught: {}", event);
			break;
		}
	}

/* Handling Item Events, message types are munged c.f. C++ API.
 */
	private void OnOMMItemEvent (OMMItemEvent event) {
		LOG.trace ("OnOMMItemEvent: {}", event);
		final OMMMsg msg = event.getMsg();

/* Verify event is a response event. */
		switch (msg.getMsgType()) {
		case OMMMsg.MsgType.REFRESH_RESP:
		case OMMMsg.MsgType.UPDATE_RESP:
		case OMMMsg.MsgType.STATUS_RESP:
		case OMMMsg.MsgType.ACK_RESP:
			this.OnRespMsg (msg, event.getHandle(), event.getClosure());
			break;

/* Request message */
		case OMMMsg.MsgType.REQUEST:
/* Generic message */
		case OMMMsg.MsgType.GENERIC:
/* Post message */
		case OMMMsg.MsgType.POST:
		default:
			LOG.trace ("Uncaught: {}", msg);
			break;
		}
	}

	private void OnRespMsg (OMMMsg msg, Handle handle, Object closure) {
		LOG.trace ("OnRespMsg: {}", msg);
		switch (msg.getMsgModelType()) {
		case RDMMsgTypes.LOGIN:
			this.OnLoginResponse (msg);
			break;

		case RDMMsgTypes.DIRECTORY:
			this.OnDirectoryResponse (msg);
			break;

		case RDMMsgTypes.DICTIONARY:
			this.OnDictionaryResponse (msg, handle, closure);
			break;

		case RDMMsgTypes.MARKET_PRICE:
			this.OnMarketPrice (msg);
			break;

		case 11 /* RDMMsgTypes.SPS */:
			this.OnSps (msg, handle, closure);
			break;

		default:
			LOG.trace ("Uncaught: {}", msg);
			break;
		}
	}

	private void OnLoginResponse (OMMMsg msg) {
		LOG.trace ("OnLoginResponse: {}", msg);
/* RFA example helper to dump incoming message. */
//GenericOMMParser.parse (msg);
		final RDMLoginResponse response = new RDMLoginResponse (msg);
		final byte stream_state = response.getRespStatus().getStreamState();
		final byte data_state	= response.getRespStatus().getDataState();

		switch (stream_state) {
		case OMMState.Stream.OPEN:
			switch (data_state) {
			case OMMState.Data.OK:
				this.OnLoginSuccess (response);
				break;

			case OMMState.Data.SUSPECT:
				this.OnLoginSuspect (response);
				break;

			default:
				LOG.trace ("Uncaught data state: {}", response);
				break;
			}
			break;

		case OMMState.Stream.CLOSED:
			this.OnLoginClosed (response);
			break;

		default:
			LOG.trace ("Uncaught stream state: {}", response);
			break;
		}
	}

/* Login Success.
 */
	private void OnLoginSuccess (RDMLoginResponse response) {
		LOG.trace ("OnLoginSuccess: {}", response);
		LOG.trace ("Unmuting consumer.");
		this.is_muted = false;
	}

/* Other Login States.
 */
	private void OnLoginSuspect (RDMLoginResponse response) {
		LOG.trace ("OnLoginSuspect: {}", response);
		LOG.debug ("{}", response.getRespStatus());
		this.is_muted = true;
	}

/* Other Login States.
 */
	private void OnLoginClosed (RDMLoginResponse response) {
		LOG.trace ("OnLoginClosed: {}", response);
		this.is_muted = true;
	}

/* MMT_DIRECTORY domain.  Request RDM dictionaries, RWFFld and RWFEnum, from first available service.
 */
	private void OnDirectoryResponse (OMMMsg msg) {
		LOG.trace ("OnDirectoryResponse: {}", msg);
//GenericOMMParser.parse (msg);

// We only desire a single directory response with UP status to request dictionaries, ignore all other updates */
		if (!this.pending_directory)
			return;

/* RFA 7.5.1.L1 raises invalid exception for Elektron Edge directory response due to hard coded capability validation. */
		final RDMDirectoryResponse response = new RDMDirectoryResponse (msg);
		if (!response.hasPayload()) {
			LOG.trace ("Ignoring directory response due to no payload.");
			return;
		}

		final RDMDirectoryResponsePayload payload = response.getPayload();
		if (!payload.hasServiceList()) {
			LOG.trace ("Ignoring directory response due to no service list.");
			return;
		}

/* Find /a/ service to request dictionary from.  It doesn't matter which as the ADS is
 * providing its own dictionary overriding anything from the provider.
 */
		String dictionary_service = null;
		for (Service service : payload.getServiceList()) {
			if (!service.hasServiceName()) {
				LOG.trace ("Ignoring listed service due to empty name.");
				continue;
			}
			if (!service.hasAction()) {
				LOG.trace ("{}: Ignoring service due to no map action {ADD|UPDATE|DELETE}.", service.getServiceName());
				continue;
			}
			if (RDMDirectory.ServiceAction.DELETE == service.getAction()) {
				LOG.trace ("{}: Ignoring service being deleted.", service.getServiceName());
				continue;
			}
			if (!service.hasStateFilter()) {
				LOG.trace ("{}: Ignoring service with no state filter as service may be unavailable.", service.getServiceName());
				continue;
			}
			final Service.StateFilter state_filter = service.getStateFilter();
			if (state_filter.hasServiceUp()) {
				if (state_filter.getServiceUp()) {
					if (state_filter.getAcceptingRequests()) {
						dictionary_service = service.getServiceName();
						break;
					} else {
						LOG.trace ("{}: Ignoring service as directory indicates it is not accepting requests.", service.getServiceName());
						continue;
					}
				} else {
					LOG.trace ("{}: Ignoring service marked as not-up.", service.getServiceName());
					continue;
				}
			} else {
				LOG.trace ("{}: Ignoring service without service state indicator.", service.getServiceName());
				continue;
			}
		}

		if (Strings.isNullOrEmpty (dictionary_service)) {
			LOG.trace ("No service available to accept dictionary requests, waiting for service change in directory update.");
			return;
		}

/* Hard code to RDM dictionary names */
		if (!this.dictionary_handle.containsKey ("RWFFld")) {
/* Local file override */
			if (!this.config.hasFieldDictionary()) {
				this.sendDictionaryRequest (dictionary_service, "RWFFld");
			} else {
				final FieldDictionary field_dictionary = this.rdm_dictionary.getFieldDictionary();
				FieldDictionary.readRDMFieldDictionary (field_dictionary, this.config.getFieldDictionary());
/* Additional meta-data only from file dictionaries */
				LOG.trace ("RDM field dictionary file \"{}\": { " +
						  "\"Desc\": \"{}\"" +
						", \"Version\": \"{}\"" +
						", \"Build\": \"{}\"" +
						", \"Date\": \"{}\"" +
						" }",
						this.config.getFieldDictionary(),
						field_dictionary.getFieldProperty ("Desc"),
						field_dictionary.getFieldProperty ("Version"),
						field_dictionary.getFieldProperty ("Build"),
						field_dictionary.getFieldProperty ("Date"));
			}
		}

		if (!this.dictionary_handle.containsKey ("RWFEnum")) {
			if (!this.config.hasEnumDictionary()) {
				this.sendDictionaryRequest (dictionary_service, "RWFEnum");
			} else {
				final FieldDictionary field_dictionary = this.rdm_dictionary.getFieldDictionary();
				FieldDictionary.readEnumTypeDef (field_dictionary, this.config.getEnumDictionary());
				LOG.trace ("RDM enumerated tables file \"{}\": { " +
						  "\"Desc\": \"{}\"" +
						", \"RT_Version\": \"{}\"" +
						", \"Build_RDMD\": \"{}\"" +
						", \"DT_Version\": \"{}\"" +
						", \"Date\": \"{}\"" +
						" }",
						this.config.getEnumDictionary(),
						field_dictionary.getEnumProperty ("Desc"),
						field_dictionary.getEnumProperty ("RT_Version"),
						field_dictionary.getEnumProperty ("Build_RDMD"),
						field_dictionary.getEnumProperty ("DT_Version"),
						field_dictionary.getEnumProperty ("Date"));
			}
		}

		if (0 == this.dictionary_handle.size()) {
			if (LOG.isDebugEnabled()) {
GenericOMMParser.initializeDictionary (this.rdm_dictionary.getFieldDictionary());
			}
			this.OnDictionaryComplete();
			this.pending_dictionary = false;
			this.resubscribe();
		}

/* Directory received and processed, ignore all future updates. */
		this.pending_directory = false;
	}

/* MMT_DICTIONARY domain.
 *
 * 5.8.4 Streaming Dictionary
 * Dictionary request can be streaming. Dictionary providers are not allowed to
 * send refresh and update data to consumers.  Instead the provider can
 * advertise a minor Dictionary change by sending a status (Section 2.2)
 * response message with a DataState of Suspect. It is the consumer’s
 * responsibility to reissue the dictionary request.
 */
	private void OnDictionaryResponse (OMMMsg msg, Handle handle, Object closure) {
		LOG.trace ("OnDictionaryResponse: {}", msg);
		final RDMDictionaryResponse response = new RDMDictionaryResponse (msg);
/* Receiving dictionary */
		if (response.hasAttrib()) {
			LOG.trace ("Dictionary {}: {}", response.getMessageType(), response.getAttrib().getDictionaryName());
		}
		if (response.getMessageType() == RDMDictionaryResponse.MessageType.REFRESH_RESP
			&& response.hasPayload() && null != response.getPayload())
		{
			this.rdm_dictionary.load (response.getPayload(), handle);
		}

/* Only know type after it is loaded. */
		final RDMDictionary.DictionaryType dictionary_type = this.rdm_dictionary.getDictionaryType (handle);

/* Received complete dictionary */
		if (response.getMessageType() == RDMDictionaryResponse.MessageType.REFRESH_RESP
			&& response.getIndicationMask().contains (RDMDictionaryResponse.IndicationMask.REFRESH_COMPLETE))
		{
			LOG.trace ("Dictionary complete.");
/* Check dictionary version */
			FieldDictionary field_dictionary = this.rdm_dictionary.getFieldDictionary();
			if (RDMDictionary.DictionaryType.RWFFLD == dictionary_type)
			{
				LOG.trace ("RDM field definitions version: {}", field_dictionary.getFieldProperty ("Version"));
			}
			else if (RDMDictionary.DictionaryType.RWFENUM == dictionary_type)
			{
/* Interesting values like Name, RT_Version, Description, Date are not provided by ADS */
				LOG.trace ("RDM enumerated tables version: {}", field_dictionary.getEnumProperty ("DT_Version"));
			}
/* Notify RFA example helper of dictionary if using to dump message content. */
GenericOMMParser.initializeDictionary (field_dictionary);
			this.dictionary_handle.get ((String)closure).setFlag();

/* Check all pending dictionaries */
			int pending_dictionaries = this.dictionary_handle.size();
			for (FlaggedHandle flagged_handle : this.dictionary_handle.values()) {
				if (flagged_handle.isFlagged())
					--pending_dictionaries;
			}
			if (0 == pending_dictionaries) {
				this.OnDictionaryComplete();
				this.pending_dictionary = false;
				this.resubscribe();
			} else {
				LOG.trace ("Dictionaries pending: {}", pending_dictionaries);
			}
		}
	}

/* Dictionaries scoped per service when not using TREP-RT as a platform. */
	private void OnDictionaryComplete() {
		LOG.trace ("All used OMM dictionaries loaded, resuming subscriptions.");
		final FieldDictionary field_dictionary = this.rdm_dictionary.getFieldDictionary();
@SuppressWarnings("unchecked")
		final Map<String, FidDef> name_map = field_dictionary.toNameMap();
		final Map<String, Integer> map = Maps.newLinkedHashMap();
		for (FidDef fid_def : name_map.values()) {
			map.put (fid_def.getName(), Integer.valueOf (fid_def.getFieldId()));
		}
		this.appendix_a = ImmutableMap.copyOf (map);
	}

/* MMT_MARKETPRICE domain.
 */
	private void OnMarketPrice (OMMMsg msg) {
		GenericOMMParser.parse (msg);
	}

/* MMT_SPS domain.
 */
	private void OnSpsStatus (DateTime dt, ResponseStatus response_status, ItemStream stream) {
/* Defer to GSON to escape status text. */
		LogMessage msg = new LogMessage (dt.toString(),
				"STATUS",
				stream.getServiceName(),
				stream.getItemName(),
				OMMState.Stream.toString (response_status.getStreamState()),
				OMMState.Data.toString (response_status.getDataState()),
				OMMState.Code.toString (response_status.getCode()),
				response_status.getText());
		LOG.info (SPS_MARKER, this.gson.toJson (msg));
	}

	private void OnSps (OMMMsg msg, Handle handle, Object closure) {
		final DateTime dt = new DateTime();
		final ItemStream item_stream = (ItemStream)closure;
		LOG.trace ("OnSps: {}", msg);
//GenericOMMParser.parse (msg);
		if (msg.isFinal()) {
			LOG.trace ("Command id for \"{}\" on service \"{}\" is closed.",
				item_stream.getItemName(), item_stream.getServiceName());
			item_stream.clearItemHandle();
		}
		if (OMMMsg.MsgType.REFRESH_RESP == msg.getMsgType()) {
/* fall through */
		}
		else if (OMMMsg.MsgType.UPDATE_RESP == msg.getMsgType()) {
			LOG.trace ("Ignoring update.");
			return;
		}
		else if (OMMMsg.MsgType.STATUS_RESP == msg.getMsgType()) {
			LOG.trace ("Status: {}", msg);

/* Stream has recovered */
			if (msg.has (OMMMsg.HAS_STATE)
				&& (OMMState.Stream.OPEN == msg.getState().getStreamState())
				&& (OMMState.Data.OK == msg.getState().getDataState()))
			{
				return;
			}

			this.OnSpsStatus (dt, new ResponseStatus (msg.getState()), item_stream);
		}
		else {
			LOG.trace ("Unhandled OMM message type ({}).", msg.getMsgType());
			return;
		}

/* break down data by container type */
		if (OMMTypes.FIELD_LIST == msg.getDataType())
		{
			final OMMFieldList field_list = (OMMFieldList)msg.getPayload();

			this.OnSpsFieldList (dt.toString(),
					item_stream.getServiceName(),
					item_stream.getItemName(),
					item_stream.getViewByFid(),
					field_list);
		}
		else if (OMMTypes.MAP == msg.getDataType())
		{
			final String dt_as_string = dt.toString();

			final OMMIterable omm_it = (OMMIterable)msg.getPayload();
/* not java.lang.iterable */
			for (Iterator it = omm_it.iterator(); it.hasNext();) {
				final OMMMapEntry map_entry = (OMMMapEntry)it.next();
				if (map_entry.getAction() != OMMMapEntry.Action.DELETE
					&& map_entry.getKey().getType() == OMMTypes.BUFFER
					&& map_entry.getDataType() == OMMTypes.FIELD_LIST)
				{
					final OMMDataBuffer data_buffer = (OMMDataBuffer)map_entry.getKey();

					this.OnSpsFieldList (dt.toString(),
							item_stream.getServiceName(),
							data_buffer.getString ("ASCII"),
							item_stream.getViewByFid(),
							(OMMFieldList)map_entry.getData());
				}
			}
		}
	}

	private void OnSpsFieldList (String dt_as_string, String service_name, String item_name, ImmutableSortedSet<Integer> view, OMMFieldList field_list) {
		if (LOG.isDebugEnabled()) {
			final Iterator<?> it = field_list.iterator();
			while (it.hasNext()) {
				final OMMFieldEntry field_entry = (OMMFieldEntry)it.next();
				final short fid = field_entry.getFieldId();
				final FidDef fid_def = rdm_dictionary.getFieldDictionary().getFidDef (fid);
				final OMMData data = field_entry.getData (fid_def.getOMMType());
				LOG.debug (new StringBuilder()
					.append (fid_def.getName())
					.append (": ")
					.append (data.isBlank() ? "null" : data.toString())
					.toString());
			}
		}

		sb.setLength (0);
		sb.append ('{')
		   .append ("\"timestamp\":\"").append (dt_as_string).append ('\"')
		  .append (",\"type\":\"REFRESH\"")
		  .append (",\"service\":\"").append (service_name).append ('\"')
		  .append (",\"recordname\":\"").append (item_name).append ('\"')
		  .append (",\"fields\":{");
/* Use field_set to also count matching FIDs in update to view */
		this.field_set.clear();
		OMMMap embedded_map = null;
		if (!field_list.isBlank() && !view.isEmpty()) {
			final Iterator<?> it = field_list.iterator();
			while (it.hasNext()) {
				final OMMFieldEntry field_entry = (OMMFieldEntry)it.next();
				final short fid = field_entry.getFieldId();
/* .contains(<short>) works with MarketFeed but not with OMM */
				if (view.contains (Integer.valueOf (fid)))
				{
					final FidDef fid_def = rdm_dictionary.getFieldDictionary().getFidDef (fid);
					final OMMData data = field_entry.getData (fid_def.getOMMType());  
					if (!this.field_set.isEmpty()) this.sb.append (',');
					if (data.isBlank()) {
						sb.append ('\"').append (fid_def.getName()).append ('\"')
						  .append (':')
						  .append ("null");
					} else {
						switch (fid_def.getOMMType()) {
/* values that can be represented raw in JSON form */
						case OMMTypes.DOUBLE:
						case OMMTypes.DOUBLE_8:
						case OMMTypes.FLOAT:  
						case OMMTypes.FLOAT_4:
						case OMMTypes.INT:
						case OMMTypes.INT_1:
						case OMMTypes.INT_2:
						case OMMTypes.INT_4:
						case OMMTypes.INT_8:
						case OMMTypes.REAL:
						case OMMTypes.REAL_4RB:
						case OMMTypes.REAL_8RB:
						case OMMTypes.UINT:
						case OMMTypes.UINT_1:
						case OMMTypes.UINT_2:
						case OMMTypes.UINT_4:
						case OMMTypes.UINT_8:
/* non-IDN types */
//						case OMMTypes.INT32:	/* deprecated warning */
//						case OMMTypes.INT64:	/* same as INT */
						case OMMTypes.UINT32:
//						case OMMTypes.UINT64:	/* same as UINT */
							sb.append ('\"').append (fid_def.getName()).append ('\"')
							  .append (':')
							  .append (data.toString());
							break;
						case OMMTypes.ENUM:
							sb.append ('\"').append (fid_def.getName()).append ('\"')
							  .append (':')
							  .append ('\"').append (rdm_dictionary.getFieldDictionary().expandedValueFor (fid, ((OMMEnum)data).getValue())).append ('\"');
							break;
/* _INS type RIC embedded */
						case OMMTypes.MAP:
LOG.info("i have a map");
							embedded_map = (OMMMap)data;
						default:
							sb.append ('\"').append (fid_def.getName()).append ('\"')
							  .append (':')
							  .append ('\"').append (data.toString()).append ('\"');
							break;
						}
					}
					this.field_set.add ((int)fid);
// cannot shortcut with embedded map fields
//					if (view.size() == this.field_set.size()) break;
				} else {
					final FidDef fid_def = rdm_dictionary.getFieldDictionary().getFidDef (fid);
					final OMMData data = field_entry.getData (fid_def.getOMMType());  
					switch (fid_def.getOMMType()) {
/* _INS type RIC embedded */
					case OMMTypes.MAP:
						embedded_map = (OMMMap)data;
					default:
						break;
					}
				}
			}
		}
		sb.append ("}}");
/* Ignore updates with no matching fields */
		if (!this.field_set.isEmpty()) {
			LOG.info (SPS_MARKER, this.sb.toString());
		}

/* Tail recursion */
		if (null != embedded_map) {
			this.sb.setLength (0);
			this.sb .append (service_name)
				.append ('.')
				.append (item_name)
				.append("_INS");
			final ItemStream item_stream = this.directory.get (this.sb.toString());
			if (null == item_stream) {
				LOG.error("Cannot find RIC {} in configuration for embedded map expansion.", this.sb.toString());
			} else {
/* not java.lang.iterable */
				for (Iterator it = embedded_map.iterator(); it.hasNext();) {
					final OMMMapEntry map_entry = (OMMMapEntry)it.next();
					if (map_entry.getAction() != OMMMapEntry.Action.DELETE
						&& map_entry.getKey().getType() == OMMTypes.BUFFER
						&& map_entry.getDataType() == OMMTypes.FIELD_LIST)
					{      
						final OMMDataBuffer data_buffer = (OMMDataBuffer)map_entry.getKey();
					       
						this.OnSpsFieldList (dt_as_string,
								service_name,
								data_buffer.getString ("ASCII"),
								item_stream.getViewByFid(),
								(OMMFieldList)map_entry.getData());
					}		       
				}
			}
		}
	}

	private void OnConnectionEvent (OMMConnectionEvent event) {
		LOG.trace ("OnConnectionEvent: {}", event);
	}

	private class LogMessage {
		private final String timestamp;
		private final String type;
		private final String service;
		private final String recordname;
		private final String stream;
		private final String data;
		private final String code;
		private final String text;

		public LogMessage (String timestamp, String type, String service, String recordname, String stream, String data, String code, String text) {
			this.timestamp = timestamp;
			this.type = type;
			this.service = service;
			this.recordname = recordname;
			this.stream = stream;
			this.data = data;
			this.code = code;
			this.text = text;
		}
	}

	private void OnMarketDataItemStatus (DateTime dt, String service_name, String item_name, MarketDataItemStatus status, boolean isEventStreamClosed) {
/* SPS error output here */
/* Rewrite to RSSL/OMM semantics, (Stream,Data,Code)
 *
 * Examples: OPEN,OK,NONE
 *	     - The item is served by the provider. The consumer application established
 *	       the item event stream.
 *
 *	     OPEN,SUSPECT,NO_RESOURCES
 *	     - The provider does not offer data for the requested item at this time.
 *	       However, the system will try to recover this item when available.
 *
 *	     CLOSED_RECOVER,SUSPECT,NO_RESOURCES
 *	     - The provider does not offer data for the requested item at this time. The
 *	       application can try to re-request the item later.
 *
 *	     CLOSED,SUSPECT,/any/
 *	     -	The item is not open on the provider, and the application should close this
 *		stream.
 */
		String stream_state = "OPEN", data_state = "NO_CHANGE";
		if (isEventStreamClosed || MarketDataItemStatus.CLOSED == status.getState())
		{
			stream_state = "CLOSED";
			data_state = "SUSPECT";
		}
		else if (MarketDataItemStatus.CLOSED_RECOVER == status.getState())
		{
			stream_state = "CLOSED_RECOVER";
			data_state = "SUSPECT";
		}
		else if (MarketDataItemStatus.STALE == status.getState())
		{
			data_state = "SUSPECT";
		}
		else if (MarketDataItemStatus.OK == status.getState())
		{
			data_state = "OK";
		}

/* Defer to GSON to escape status text. */
		LogMessage msg = new LogMessage (dt.toString(),
				"STATUS",
				service_name,
				item_name,
				stream_state,
				data_state,
				status.getStatusCode().toString(),
				status.getStatusText());
		LOG.info (SPS_MARKER, this.gson.toJson (msg));
	}

	private void OnMarketDataItemEvent (MarketDataItemEvent event) {
		final DateTime dt = new DateTime();
		final ItemStream item_stream = (ItemStream)event.getClosure();
		LOG.trace ("OnMarketDataItemEvent: {}", event);
		if (event.isEventStreamClosed()) {
			LOG.trace ("Subscription handle for \"{}\" is closed.", event.getItemName());
			item_stream.clearItemHandle();
		}
/* strings in switch are not supported in -source 1.6 */
		if (MarketDataItemEvent.UPDATE == event.getMarketDataMsgType()
			|| MarketDataItemEvent.IMAGE == event.getMarketDataMsgType()
			|| MarketDataItemEvent.UNSOLICITED_IMAGE == event.getMarketDataMsgType())
		{
/* fall through */
		}
		else if (MarketDataItemEvent.STATUS == event.getMarketDataMsgType()) {
			LOG.trace ("Status: {}", event);

/* MARKET_DATA_ITEM_EVENT, service = ELEKTRON_EDGE, item = RBK,
 * MarketDataMessageType = STATUS, MarketDataItemStatus = { state: CLOSED,
 * code: NONE, text: "The record could not be found"}, data = NULL
 */

/* Item stream recovered. */
			if (MarketDataItemStatus.OK == event.getStatus().getState())
				return;

			this.OnMarketDataItemStatus (dt,
					item_stream.getServiceName(),
					item_stream.getItemName(),
					event.getStatus(),
					event.isEventStreamClosed());
			return;
		}
/* Available in SSL if useMarketfeedUpdateType set True so that updates are inspected for
 * underlying type, whether Correction (317) or a Closing Run (312).
 */
		else if (MarketDataItemEvent.CORRECTION == event.getMarketDataMsgType()) {
			LOG.trace ("Ignoring correction.");
			return;
		}
		else if (MarketDataItemEvent.CLOSING_RUN == event.getMarketDataMsgType()) {
			LOG.trace ("Ignoring closing run.");
			return;
		}
		else if (MarketDataItemEvent.RENAME == event.getMarketDataMsgType()) {
			LOG.trace ("Ignoring rename.");
			return;
		}
		else if (MarketDataItemEvent.PERMISSION_DATA == event.getMarketDataMsgType()) {
			LOG.trace ("Ignoring permission data.");
			return;
		}
/* GROUP_CHANGE is deprecated */
		else {
			LOG.trace ("Unhandled market data message type ({}).", event.getMarketDataMsgType());
			return;
		}

		if (MarketDataEnums.DataFormat.MARKETFEED != event.getDataFormat()) {
			this.sb.setLength (0);
			switch (event.getDataFormat()) {
			case MarketDataEnums.DataFormat.UNKNOWN:
				this.sb.append ("Unknown");
				break;
			case MarketDataEnums.DataFormat.ANSI_PAGE:
				this.sb.append ("ANSI_Page");
				break;
			case MarketDataEnums.DataFormat.MARKETFEED:
				this.sb.append ("Marketfeed");
				break;
			case MarketDataEnums.DataFormat.QFORM:
				this.sb.append ("QForm");
				break;
/* TibMsg self-describing */
			case MarketDataEnums.DataFormat.TIBMSG:
				this.sb.append ("TibMsg");
				break;
			case MarketDataEnums.DataFormat.IFORM:
			default:
				this.sb.append (event.getDataFormat());
				break;
			}

			LOG.trace ("Unsupported data format ({}) in market data item event.", this.sb.toString());
			return;
		}

		final byte[] data = event.getData();
		final int length = (data != null ? data.length : 0);
		if (0 == length) return;

		try {
			this.msg.UnPack (data);
			if (LOG.isDebugEnabled()) {
				for (int status = this.field.First (msg);
					TibMsg.TIBMSG_OK == status;
					status = this.field.Next())
				{
					LOG.debug (new StringBuilder()
						.append (this.field.Name())
						.append (": ")
						.append (this.field.StringData())
						.toString());
				}
			}

/* SPS output here, do not use GSON as fields map would be expensive to create. */
			final String dt_as_string = dt.toString();
			this.sb.setLength (0);
			this.sb .append ('{')
				 .append ("\"timestamp\":\"").append (dt_as_string).append ('\"')
				.append (",\"type\":\"").append (event.getMarketDataMsgType()).append ('\"')
				.append (",\"service\":\"").append (item_stream.getServiceName()).append ('\"')
				.append (",\"recordname\":\"").append (item_stream.getItemName()).append ('\"')
				.append (",\"fields\":{");
/* Use field_set to also count matching FIDs in update to view */
			this.field_set.clear();
			if (item_stream.hasViewByFid()) {
				final ImmutableSortedSet<Integer> view = item_stream.getViewByFid();
				for (int status = this.field.First (msg);
					TibMsg.TIBMSG_OK == status;
					status = this.field.Next())
				{
					if (view.contains (field.MfeedFid()))
					{
						final boolean is_blank = (0 == this.field.RawSize());
						if (!this.field_set.isEmpty()) this.sb.append (',');
						if (is_blank) {
							this.sb.append ('{')
								.append ('\"').append (this.field.Name()).append ('\"')
								.append (':')
								.append ("null")
								.append ('}');
						} else {
							boolean is_zero = false;
							final String field_data = this.field.StringData();
							switch (this.field.Type()) {
/* values that can be represented raw in JSON form */
							case TibMsg.TIBMSG_INT:
							case TibMsg.TIBMSG_REAL:
							case TibMsg.TIBMSG_UINT:
/* IEEE 754 ensures +0.0 == -0.0 */
								is_zero = (0.0 == this.field.DoubleData());
								this.sb.append ('{')
									.append ('\"').append (this.field.Name()).append ('\"')
									.append (':')
									.append (field_data)
									.append ('}');
								break;
/* empty strings, timestamps are left as is */
							default:
								this.sb.append ('{')
									.append ('\"').append (this.field.Name()).append ('\"')
									.append (':')
									.append ('\"').append (field_data).append ('\"')
									.append ('}');
								break;
							}
						}
						this.field_set.add (this.field.MfeedFid());
						if (view.size() == this.field_set.size()) break;
					}
				}
			}
			this.sb.append ("}}");
/* Ignore updates with no matching fields */
			if (!this.field_set.isEmpty()) {
				LOG.info (SPS_MARKER, this.sb.toString());
			}
		} catch (TibException e) {
			LOG.trace ("Unable to unpack data with TibMsg: {}", e.getMessage());
		}
	}

/* In RMDS land we may have MarketFeed or SASS dictionaries dependent upon the infrastructure
 * and providers.  Dynamically support both at runtime by requesting all available dictionaries.
 * Note state will stall if a dictionary is advertised but not available.  There is no support
 * for different versions of the same dictionary across different providers.
 */
	private void OnMarketDataSvcEvent (MarketDataSvcEvent event) {
		LOG.trace ("OnMarketDataSvcEvent: {}", event);
/* We only desire a single directory response with UP status to request dictionaries, ignore all other updates */
		if (!this.pending_directory)
			return;
/* Wait for any service to be up instead of one named service */
		if (/* event.getServiceName().equals (this.config.getServiceName())
			&& */ MarketDataSvcStatus.UP == event.getStatus().getState())
		{
/* start dictionary subscription */
			final DataDictInfo[] dataDictInfo = event.getDataDictInfo();
			for (int i = 0; i < dataDictInfo.length; ++i) {
				if (!this.dictionary_handle.containsKey (dataDictInfo[i].getDictType().toString())) 
					this.addDictionarySubscription (dataDictInfo[i]);
			}

			if (this.dictionary_handle.isEmpty()) {
				LOG.trace ("No dictionary available to request, waiting for dictionary information in directory update.");
				return;
			}
		}
	}

	private void OnMarketDataDictComplete() {
		LOG.trace ("All used MarketFeed dictionaries loaded, resuming subscriptions.");
		this.appendix_a = this.createDictionaryMap();
	}

	private void OnMarketDataDictEvent (MarketDataDictEvent event) {
		LOG.trace ("OnMarketDataDictEvent: {}", event);
		if (MarketDataDictStatus.OK == event.getStatus().getState()) {
			final byte[] data = event.getData();
			final int length = (data != null ? data.length : 0);
			if (0 == length) return;

			try {
/* Use new message object so not to waste space */
				TibMsg msg = new TibMsg();
				msg.UnPack (data);
				if (DataDictInfo.MARKETFEED == event.getDataDictInfo().getDictType()) {
					TibMsg.UnPackMfeedDictionary (msg);
					LOG.trace ("MarketFeed dictionary unpacked.");
				}
			} catch (TibException e) {
				LOG.trace ("Unable to unpack dictionary with TibMsg: {}", e.getMessage());
				return;
			}
			
			this.dictionary_handle.get ((String)event.getClosure()).setFlag();
/* Check all pending dictionaries */
			int pending_dictionaries = this.dictionary_handle.size();
			for (FlaggedHandle flagged_handle : this.dictionary_handle.values()) {
				if (flagged_handle.isFlagged())
					--pending_dictionaries;
			}
			if (0 == pending_dictionaries) {
				this.OnMarketDataDictComplete();
				this.pending_dictionary = false;
				this.resubscribe();
			} else {
				LOG.trace ("Dictionaries pending: {}", pending_dictionaries);
			}
		}
	}

	private void OnConnectionEvent (ConnectionEvent event) {
		LOG.trace ("OnConnectionEvent: {}", event);
	}

	private void OnEntitlementsAuthenticationEvent (EntitlementsAuthenticationEvent event) {
		LOG.trace ("OnEntitlementsAuthenticationEvent: {}", event);
	}
}

/* eof */
