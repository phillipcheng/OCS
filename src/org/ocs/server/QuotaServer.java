
package org.ocs.server;

import java.io.IOException;
import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Set;

import javax.sql.DataSource;

import org.apache.log4j.Logger;
import org.jdiameter.api.Answer;
import org.jdiameter.api.ApplicationId;
import org.jdiameter.api.Avp;
import org.jdiameter.api.AvpDataException;
import org.jdiameter.api.AvpSet;
import org.jdiameter.api.Configuration;
import org.jdiameter.api.InternalException;
import org.jdiameter.api.MetaData;
import org.jdiameter.api.Network;
import org.jdiameter.api.NetworkReqListener;
import org.jdiameter.api.Request;
import org.jdiameter.api.Session;
import org.jdiameter.api.SessionFactory;
import org.jdiameter.api.Stack;
import org.jdiameter.api.StackType;
import org.jdiameter.client.impl.DictionarySingleton;
import org.jdiameter.server.impl.StackImpl;
import org.jdiameter.server.impl.helpers.XMLConfiguration;
import org.mobicents.diameter.dictionary.AvpDictionary;
import org.ocs.util.MessageLogger;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

/**
 * @author baranowb
 * 
 */
public class QuotaServer implements NetworkReqListener {
	private static final Logger log = Logger.getLogger(QuotaServer.class);

	private static final String configFile = "ocs/server-jdiameter-config.xml";
	private static final String dictionaryFile = "ocs/dictionary.xml";

	// Defs for our app
	private static final int commandCode = 16777214;
	private static final long vendorID = 999999;
	private static final long applicationID = 16777215;
	private ApplicationId authAppId = ApplicationId.createByAuthAppId(applicationID);

	private static final int opTypeCode = 400000; // INTEGER32
	private static final int UserIdCode = 400001; // OCTETSTRING
	private static final int TenantIdCode = 400005; // OCTETSTRING
	private static final int requestQuotaCode = 400002; // UNSIGNED64
	private static final int usedQuotaCode = 400003; // UNSIGNED64
	private static final int grantedQuotaCode = 400004; // UNSIGNED64

	// enum values for OpType AVP
	private static final int OP_TYPE_INITIAL = 1;
	private static final int OP_TYPE_UPDATE = 2;
	private static final int OP_TYPE_TERMINATING = 3;

	private AvpDictionary dictionary = AvpDictionary.INSTANCE;
	private Stack stack;
	private JdbcTemplate jdbcTemplateObject;
	private QuotaSessionManager quotaSessionManager = QuotaSessionManager.getInstance();

	private boolean finished = false;

	private void initStack() {
		if (log.isInfoEnabled()) {
			log.info("Initializing Stack...");
		}
		ApplicationContext context = new ClassPathXmlApplicationContext("beans.xml");
		DataSource dataSource = context.getBean(DataSource.class);
		jdbcTemplateObject = new JdbcTemplate(dataSource);
		if (log.isDebugEnabled()) {
			jdbcTemplateObject.query("select * from QUOTA", new RowMapper() {
	
				@Override
				public Object mapRow(ResultSet arg0, int arg1) throws SQLException {
					System.out.println("user:" + arg0.getString("username"));
					System.out.println("  telant:" + arg0.getString("tenantid"));
					return null;
				}
			});
		}
		
		quotaSessionManager.setJdbcTemplate(jdbcTemplateObject);

		InputStream is = null;
		try {
			DictionarySingleton.getDictionary().configure(
					this.getClass().getClassLoader().getResourceAsStream(dictionaryFile));
			// What the fuck!
			dictionary.parseDictionary((InputStream) null);

			log.info("AVP Dictionary successfully parsed.");
			this.stack = new StackImpl();

			is = this.getClass().getClassLoader().getResourceAsStream(configFile);

			Configuration config = new XMLConfiguration(is);
			stack.init(config);
			if (log.isInfoEnabled()) {
				log.info("Stack Configuration successfully loaded.");
			}

			Set<org.jdiameter.api.ApplicationId> appIds = stack.getMetaData().getLocalPeer().getCommonApplications();

			log.info("Diameter Stack  :: Supporting " + appIds.size() + " applications.");
			for (org.jdiameter.api.ApplicationId x : appIds) {
				log.info("Diameter Stack  :: Common :: " + x);
			}
			is.close();
			Network network = stack.unwrap(Network.class);
			network.addNetworkReqListener(this, this.authAppId);
		} catch (Exception e) {
			e.printStackTrace();
			if (this.stack != null) {
				this.stack.destroy();
			}

			if (is != null) {
				try {
					is.close();
				} catch (IOException e1) {
					e1.printStackTrace();
				}
			}
			return;
		}

		MetaData metaData = stack.getMetaData();
		if (metaData.getStackType() != StackType.TYPE_SERVER || metaData.getMinorVersion() <= 0) {
			stack.destroy();
			if (log.isEnabledFor(org.apache.log4j.Level.ERROR)) {
				log.error("Incorrect driver");
			}
			return;
		}

		try {
			if (log.isInfoEnabled()) {
				log.info("Starting stack");
			}
			stack.start();
			if (log.isInfoEnabled()) {
				log.info("Stack is running.");
			}
		} catch (Exception e) {
			e.printStackTrace();
			stack.destroy();
			return;
		}
		if (log.isInfoEnabled()) {
			log.info("Stack initialization successfully completed.");
		}
	}

	/**
	 * @return
	 */
	private boolean finished() {
		return this.finished;
	}

	public static void main(String[] args) {
		QuotaServer es = new QuotaServer();
		es.initStack();

		while (!es.finished()) {
			try {
				Thread.currentThread().sleep(5000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public Answer processRequest(Request request) {
		MessageLogger.dumpMessage(request, false);
		Answer answer = processRequest0(request);
		MessageLogger.dumpMessage(answer, true);
		return answer;
	}

	public Answer processRequest0(Request request) {
		if (request.getCommandCode() != commandCode) {
			log.error("Received bad answer: " + request.getCommandCode());
			return null;
		}

		AvpSet requestAvpSet = request.getAvps();

		Avp reqTypeAvp = requestAvpSet.getAvp(opTypeCode, vendorID);
		if (reqTypeAvp == null) {
			log.error("Request does not have Op-Type");

			Answer answer = createAnswer(request, 5004, OP_TYPE_TERMINATING);
			MessageLogger.dumpMessage(answer, true);
			return answer;
		}

		try {

			//Session session = null;
			QuotaSession quotaSession = null;

			switch ((int) reqTypeAvp.getInteger32()) {
			case OP_TYPE_INITIAL: {
				String userId = requestAvpSet.getAvp(UserIdCode, vendorID).getUTF8String();
				String tenantId = requestAvpSet.getAvp(TenantIdCode, vendorID).getUTF8String();
				// create session;
				// session = this.factory.getNewSession(request.getSessionId());
				quotaSession = quotaSessionManager.initial(request.getSessionId(), userId, tenantId);
				
				if (quotaSession.getBalance() < 0) {
					//means user not exist or balance is empty
					Answer answer = createAnswer(request, 5003, OP_TYPE_INITIAL);
					return answer;
				}
				
				long requestQuota = requestAvpSet.getAvp(requestQuotaCode, vendorID).getUnsigned64();

				long grantQuota = quotaSession.reserveQuota(requestQuota, 0);

				log.info("requestQuota:" + requestQuota + " , grantQuota:" + grantQuota);

				Answer answer = createAnswer(request, 2001, OP_TYPE_INITIAL);

				try {
					// code , value , vendor, mandatory,protected
					answer.getAvps().addAvp(grantedQuotaCode, grantQuota, vendorID, true, false, false);
				} catch (Exception e) {
					e.printStackTrace();
				}

				return answer;
			}
			case OP_TYPE_UPDATE: {

				quotaSession = quotaSessionManager.retrieve(request.getSessionId());
				if (quotaSession == null) {
					Answer answer = createAnswer(request, 5002, OP_TYPE_INITIAL);
					return answer;
				}
				
				long requestQuota = requestAvpSet.getAvp(requestQuotaCode, vendorID).getUnsigned64();
				long usedQuota = requestAvpSet.getAvp(usedQuotaCode, vendorID).getUnsigned64();

				long grantQuota = quotaSession.reserveQuota(requestQuota, usedQuota);

				log.info("requestQuota:" + requestQuota + ", usedQuota:" + usedQuota + ", grantQuota:" + grantQuota);

				Answer answer = createAnswer(request, 2001, OP_TYPE_UPDATE);

				try {
					// code , value , vendor, mandatory,protected
					answer.getAvps().addAvp(grantedQuotaCode, grantQuota, vendorID, true, false, false);
				} catch (Exception e) {
					e.printStackTrace();
				}

				return answer;
			}
			case OP_TYPE_TERMINATING: {
				
				quotaSession = quotaSessionManager.destroy(request.getSessionId());
				if (quotaSession == null) {
					Answer answer = createAnswer(request, 5002, OP_TYPE_INITIAL);
					return answer;
				}

				long usedQuota = requestAvpSet.getAvp(usedQuotaCode, vendorID).getUnsigned64();

				log.info("usedQuota:" + usedQuota);

				quotaSession.reserveQuota(0, usedQuota);

				// release session and its resources.
				// session.release();

				Answer answer = createAnswer(request, 2001, OP_TYPE_TERMINATING);
				return answer;
			}
			default:
				log.error("Bad value of Exchange-Type avp: " + reqTypeAvp.getUnsigned32());
				//TODO 5004
				break;
				
			}
		} catch (Exception e) {
			// thrown when interpretation of byte[] fails
			e.printStackTrace();
			//TODO
		}

		return null;
	}

	/**
	 * 
	 * @param requestQuota
	 * @param usedQuota
	 * @return grantedQuota
	 */
	private long reserveQuota(String userId, long requestQuota, long usedQuota) {

		final long[] quotas = new long[] { -1, -1 };

		jdbcTemplateObject.query("select balance,reserved from QUOTA where username = ?", 
				new String[] { userId },
				new int[]{Types.VARCHAR},
				new RowCallbackHandler() {
					@Override
					public void processRow(ResultSet arg0) throws SQLException {
						// balance
						quotas[0] = arg0.getLong("balance");
						// reserved
						quotas[1] = arg0.getLong("reserved");
					}
				});
		log.info(String.format("get user %s quota, balance: %d, reserved: %d", 
				userId, quotas[0], quotas[1]));
		if (quotas[0] <= 0) {
			// no balance at all
			return 0;
		}

		boolean needUpdate = false;

		if (usedQuota > 0) {
			//TODO what if there are two reserve before any used
			// need clear previous reserved
			quotas[1] = 0;

			// update real balance
			quotas[0] -= usedQuota;

			needUpdate = true;
		}
		
		long leftQuota = quotas[0] - quotas[1];

		if (leftQuota >= requestQuota) {
			// good
			quotas[1] += requestQuota;
			needUpdate = true;
		} else if (leftQuota > 0) {
			// not enough, give all
			quotas[1] += leftQuota;
			needUpdate = true;
		} else {
			// nothing left
		}

		if (needUpdate) {
			log.info("update QUOTA set balance = " + quotas[0] + ", reserved = " + quotas[0] + " where username = "
					+ userId);
			
			jdbcTemplateObject.update("update QUOTA set balance = ?, reserved = ? where username = ? ", 
					new Object[]{quotas[0],quotas[1], userId},
					new int[]{Types.BIGINT, Types.BIGINT, Types.VARCHAR});
		}

		return quotas[1];
	}

	private Answer createAnswer(Request r, int resultCode, int enumType) {
		Answer answer = r.createAnswer(resultCode);
		AvpSet answerAvps = answer.getAvps();
		try {
			// code , value , vendor, mandatory,protected
			answerAvps.addAvp(opTypeCode, r.getAvps().getAvp(opTypeCode, vendorID).getInteger32(), vendorID, true,
					false, true);

			// add origin, its required by duplicate detection
			answerAvps
					.addAvp(Avp.ORIGIN_HOST, stack.getMetaData().getLocalPeer().getUri().getFQDN(), true, false, true);
			answerAvps.addAvp(Avp.ORIGIN_REALM, stack.getMetaData().getLocalPeer().getRealmName(), true, false, true);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return answer;
	}
}
