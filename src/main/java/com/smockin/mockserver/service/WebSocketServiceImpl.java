package com.smockin.mockserver.service;

import com.smockin.admin.exception.RecordNotFoundException;
import com.smockin.admin.exception.ValidationException;
import com.smockin.admin.persistence.dao.RestfulMockDAO;
import com.smockin.admin.persistence.entity.RestfulMock;
import com.smockin.admin.service.utils.UserTokenServiceUtils;
import com.smockin.admin.websocket.LiveLoggingHandler;
import com.smockin.mockserver.engine.MockedRestServerEngine;
import com.smockin.mockserver.exception.MockServerException;
import com.smockin.mockserver.service.dto.PushClientDTO;
import com.smockin.mockserver.service.dto.WebSocketDTO;
import com.smockin.utils.GeneralUtils;
import com.smockin.utils.LiveLoggingUtils;
import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by mgallina.
 */
@Service
@Transactional
public class WebSocketServiceImpl implements WebSocketService {

    private final Logger logger = LoggerFactory.getLogger(WebSocketServiceImpl.class);

    private final String WS_HAND_SHAKE_KEY = "Sec-WebSocket-Accept";

    @Autowired
    private RestfulMockDAO restfulMockDAO;

    @Autowired
    private MockedRestServerEngine mockedRestServerEngine;

    @Autowired
    private UserTokenServiceUtils userTokenServiceUtils;

    @Autowired
    private LiveLoggingHandler liveLoggingHandler;

    // TODO Should add TTL and scheduled sweeper to stop the sessionMap from building up.
    // A map of web socket client sessions per simulated web socket path
    private final ConcurrentHashMap<String, Set<SessionIdWrapper>> sessionMap = new ConcurrentHashMap<>();

    /**
     *
     * Stores all websocket client sessions in the internal map 'sessionMap'.
     *
     * Note sessions are 'internally' identified using the encrypted handshake 'Sec-WebSocket-Accept' value and
     * 'externally' identified using an allocated UUID.
     *
     *
     * @param mockExtId
     * @param path
     * @param idleTimeoutMillis
     * @param session
     *
     */
    public void registerSession(final String mockExtId, final String path, final long idleTimeoutMillis, final boolean proxyPushIdOnConnect, final Session session, final boolean logMockCalls) {
        logger.debug("registerSession called");

        session.setIdleTimeout((idleTimeoutMillis > 0) ? idleTimeoutMillis : MAX_IDLE_TIMEOUT_MILLIS );

        final Set<SessionIdWrapper> sessions = sessionMap.getOrDefault(path, new HashSet<>());
        final String assignedId = GeneralUtils.generateUUID();
        final String traceId = session.getUpgradeResponse().getHeader(GeneralUtils.LOG_REQ_ID);

        sessions.add(new SessionIdWrapper(assignedId, traceId, session, GeneralUtils.getCurrentDate(), logMockCalls));

        sessionMap.put(path, sessions);

        if (proxyPushIdOnConnect) {
            sendMessage(assignedId, new WebSocketDTO(path, "clientId: " + assignedId));
        }

        liveLoggingHandler.broadcast(LiveLoggingUtils.buildLiveLogOutboundDTO(traceId, 101, null, "Websocket established (clientId: " + assignedId + ")", false, false));

        if (logMockCalls)
            LiveLoggingUtils.MOCK_TRAFFIC_LOGGER.info(LiveLoggingUtils.buildLiveLogOutboundFileEntry(traceId, 101, null, "Websocket established (clientId: " + assignedId + ")", false, false));

    }

    /**
     *
     * Removes the closing client's session from 'sessionMap' (using the handshake identifier).
     *
     * @param session
     */
    public void removeSession(final Session session) {
        logger.debug("removeSession called");

        final String sessionHandshake = session.getUpgradeResponse().getHeader(WS_HAND_SHAKE_KEY);

        sessionMap.values().forEach(sessionSet ->
            sessionSet.forEach( s -> {
                if (s.getSession().getUpgradeResponse().getHeader(WS_HAND_SHAKE_KEY).equals(sessionHandshake)) {
                    sessionSet.remove(s);

                    liveLoggingHandler.broadcast(LiveLoggingUtils.buildLiveLogOutboundDTO(s.getTraceId(), null, null, "Websocket closed", false, false));

                    if (s.isLogMockCalls())
                        LiveLoggingUtils.MOCK_TRAFFIC_LOGGER.info(LiveLoggingUtils.buildLiveLogOutboundFileEntry(s.getTraceId(), null, null, "Websocket closed", false, false));

                    return;
                }
            })
        );

    }

    public void sendMessage(final String id, final WebSocketDTO dto) throws MockServerException {
        logger.debug("sendMessage called");

        dto.setBody(GeneralUtils.removeAllLineBreaks(dto.getBody()));

        final Set<SessionIdWrapper> sessions = sessionMap.get(dto.getPath());

        if (sessions == null) {
            return;
        }

        // Push to specific client session for the given handshake id
        sessions.stream()
            .filter(s -> (s.getId().equals(id)))
            .findFirst()
            .ifPresent(s -> {
                try {
                    s.getSession().getRemote().sendString(dto.getBody());
                    liveLoggingHandler.broadcast(LiveLoggingUtils.buildLiveLogOutboundDTO(s.getTraceId(), null, null, dto.getBody(), false, false));

                    if (s.isLogMockCalls())
                        LiveLoggingUtils.MOCK_TRAFFIC_LOGGER.info(LiveLoggingUtils.buildLiveLogOutboundFileEntry(s.getTraceId(), null, null, dto.getBody(), false, false));

                } catch (IOException e) {
                    throw new MockServerException(e);
                }
            });

    }

    public List<PushClientDTO> getClientConnections(final String mockExtId, final String token) throws RecordNotFoundException, ValidationException {

        final RestfulMock mock = restfulMockDAO.findByExtId(mockExtId);

        if (mock == null)
            throw new RecordNotFoundException();

        userTokenServiceUtils.validateRecordOwner(mock.getCreatedBy(), token);

        final String prefixedPath = mockedRestServerEngine.buildUserPath(mock);
        final List<PushClientDTO> sessionHandshakeIds = new ArrayList<>();

        if (!sessionMap.containsKey(prefixedPath)) {
            return sessionHandshakeIds;
        }

        sessionMap.get(prefixedPath)
                .forEach(s -> sessionHandshakeIds.add(new PushClientDTO(s.getId(), s.getDateJoined())));

        return sessionHandshakeIds;
    }

    public String getExternalId(final String path, final Session session) {

        final String sessionHandshake = session.getUpgradeResponse().getHeader(WS_HAND_SHAKE_KEY);

        for (SessionIdWrapper sw : sessionMap.get(path)) {
            if (sw.getSession().getUpgradeResponse().getHeader(WS_HAND_SHAKE_KEY).equals(sessionHandshake)) {
                return sw.getId();
            }
        }

        return null;
    }

    /**
     * Private class used to associate each WebSocket session against an allocated UUID.
     *
     * The UUID is used as an external identifier.
     *
     */
    private final class SessionIdWrapper {

        private final String id;
        private final String traceId;
        private final Session session;
        private final Date dateJoined;
        private final boolean logMockCalls;

        public SessionIdWrapper(final String id, final String traceId, final Session session, final Date dateJoined, final boolean logMockCalls) {
            this.id = id;
            this.traceId = traceId;
            this.session = session;
            this.dateJoined = dateJoined;
            this.logMockCalls = logMockCalls;
        }

        public String getId() {
            return id;
        }
        public String getTraceId() {
            return traceId;
        }
        public Session getSession() {
            return session;
        }
        public Date getDateJoined() {
            return dateJoined;
        }
        public boolean isLogMockCalls() {
            return logMockCalls;
        }
    }

    public void clearSession() {
        sessionMap.clear();
    }

}
