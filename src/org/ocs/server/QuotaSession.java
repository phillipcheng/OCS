package org.ocs.server;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import org.apache.log4j.Logger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

public class QuotaSession {
	private static final Logger log = Logger.getLogger(QuotaSession.class);

	private long balance = -1;

	private long reserved;

	private String userId;
	private JdbcTemplate jdbcTemplate;
	private String sessionId;
	private String tenantId;

	public QuotaSession(String userId, String tenantId, String sessionId, JdbcTemplate jdbcTemplate) {
		super();
		this.userId = userId;
		this.tenantId = tenantId;
		this.sessionId = sessionId;
		this.jdbcTemplate = jdbcTemplate;

		loadQuota();
	}

	public void loadQuota() {
		jdbcTemplate.query("select balance,reserved from QUOTA where username = ? and tenantid = ?", new Object[] { userId , Integer.parseInt(tenantId)},
				new int[] { Types.VARCHAR, Types.INTEGER }, new RowCallbackHandler() {

					@Override
					public void processRow(ResultSet arg0) throws SQLException {
						// balance
						balance = arg0.getLong("balance");
						// reserved
						reserved = arg0.getLong("reserved");
					}
				});
		log.info(String.format("get user %s quota, balance: %d, reserved: %d", userId, balance, reserved));

	}

	public void persistQuota() {
		log.info("update QUOTA set balance = " + balance + ", reserved = " + reserved + " where username = " + userId +  " tenantid = " + tenantId);

		jdbcTemplate.update("update QUOTA set balance = ?, reserved = ? where username = ?  and tenantid = ?", new Object[] { balance,
				reserved, userId, Integer.parseInt(tenantId) }, new int[] { Types.BIGINT, Types.BIGINT, Types.VARCHAR, Types.INTEGER });

	}

	public long getBalance() {
		return balance;
	}

	public long getReserved() {
		return reserved;
	}

	public long reserveQuota(long requestQuota, long usedQuota) {

		if (balance <= 0) {
			// no balance at all
			return 0;
		}

		boolean needUpdate = false;

		if (usedQuota > 0) {
			//TODO what if there are two reserve before any used
			// need clear previous reserved
			reserved = 0;

			// update real balance
			balance -= usedQuota;

			needUpdate = true;
		}
		
		long leftQuota = balance - reserved;
		long grantedQuota = 0;

		if (leftQuota >= requestQuota) {
			// good
			reserved += requestQuota;
			grantedQuota = requestQuota;
			needUpdate = true;
		} else if (leftQuota > 0) {
			// not enough, give all
			reserved += leftQuota;
			grantedQuota = leftQuota;
			needUpdate = true;
		} else {
			// nothing left
		}

		if (needUpdate) {
			persistQuota();
		}

		return grantedQuota;
	}

}
