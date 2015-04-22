package org.ocs.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.JdbcTemplate;

public class QuotaSessionManager {

	private static final QuotaSessionManager instance = new QuotaSessionManager();

	public static QuotaSessionManager getInstance() {
		return instance;
	}

	private QuotaSessionManager() {
	}

	private JdbcTemplate jdbcTemplate;

	public void setJdbcTemplate(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	//TODO session time out check?
	private Map<String, QuotaSession> sessions = new ConcurrentHashMap<String, QuotaSession>();

	public QuotaSession initial(String sessionId, String userId, String tenantId) {
		QuotaSession s = new QuotaSession(userId, tenantId, sessionId, jdbcTemplate);
		sessions.put(sessionId, s);
		return s;
	}

	public QuotaSession retrieve(String sessionId) {
		return sessions.get(sessionId);
	}

	public QuotaSession destroy(String sessionId) {
		QuotaSession s = sessions.remove(sessionId);
		return s;
	}
}
