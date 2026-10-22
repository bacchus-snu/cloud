// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.oidc;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.acl.RoleVO;
import org.apache.cloudstack.acl.dao.RoleDao;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.ConfigKeyScheduledExecutionWrapper;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.oidc.api.command.CreateOidcProviderCmd;
import org.apache.cloudstack.oidc.api.command.DeleteOidcProviderCmd;
import org.apache.cloudstack.oidc.api.command.ListOidcProvidersCmd;
import org.apache.cloudstack.oidc.api.command.OidcCallbackCmd;
import org.apache.cloudstack.oidc.api.command.OidcLoginCmd;
import org.apache.cloudstack.oidc.api.command.UpdateOidcProviderCmd;
import org.apache.cloudstack.oidc.api.response.OidcProviderResponse;
import org.apache.cloudstack.oidc.dao.OidcLoginRequestDao;
import org.apache.cloudstack.oidc.dao.OidcProviderDao;
import org.apache.cloudstack.oidc.dao.OidcUserLinkDao;
import org.apache.cloudstack.oidc.membership.OidcMembershipSource;
import org.apache.cloudstack.oidc.membership.OidcMembershipSources;
import org.apache.cloudstack.oidc.vo.OidcLoginRequestVO;
import org.apache.cloudstack.oidc.vo.OidcProviderVO;
import org.apache.cloudstack.oidc.vo.OidcUserLinkVO;
import org.apache.cloudstack.resourcedetail.UserDetailVO;
import org.apache.cloudstack.resourcedetail.dao.UserDetailsDao;
import org.apache.commons.lang3.StringUtils;

import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.event.ActionEventUtils;
import com.cloud.event.EventTypes;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.security.SecurityGroupManager;
import com.cloud.user.Account;
import com.cloud.user.AccountDetailVO;
import com.cloud.user.AccountDetailsDao;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserAccount;
import com.cloud.user.UserAccountVO;
import com.cloud.user.UserVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserAccountDao;
import com.cloud.user.dao.UserDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class OidcAuthManagerImpl extends ManagerBase implements OidcAuthManager, Configurable {

    /**
     * Account detail ("true") with which a sign-in service in front of CloudStack marked the accounts it created; this
     * module takes such an account over at its person's first sign-in.
     */
    static final String GATE_MARK = "gate";
    /** Account detail with which that service marked the accounts it disabled, with the value {@link #GATE_MARK}. */
    static final String GATE_DISABLED_BY = "disabledby";
    static final String STALE_EMAIL_DOMAIN = "@stale.invalid";
    static final String RECONCILE_LOCK = "oidc.reconcile";
    static final Pattern PROVIDER_NAME = Pattern.compile("[a-z][a-z0-9]{0,63}");
    /** Parameters of the authorization request that the protocol sets; extra parameters may not replace them. */
    static final Set<String> PROTOCOL_PARAMETERS = Set.of("response_type", "client_id", "redirect_uri", "scope", "state", "nonce", "code_challenge",
            "code_challenge_method", "response_mode", "request", "request_uri");
    /** Claims that describe the ID token itself; the userinfo response does not replace them. */
    static final Set<String> TOKEN_CLAIMS = Set.of("iss", "sub", "aud", "azp", "exp", "iat", "nbf", "nonce", "auth_time", "acr", "amr", "at_hash",
            "c_hash", "jti");
    static final int LOCK_TIMEOUT_SECONDS = 30;
    static final long PURGE_INTERVAL_SECONDS = 60;
    private static final long FIRST_RECONCILE_DELAY_SECONDS = 60;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Inject
    protected OidcProviderDao providerDao;
    @Inject
    protected OidcLoginRequestDao loginRequestDao;
    @Inject
    protected OidcUserLinkDao linkDao;
    @Inject
    protected AccountManager accountManager;
    @Inject
    protected AccountDao accountDao;
    @Inject
    protected UserDao userDao;
    @Inject
    protected UserAccountDao userAccountDao;
    @Inject
    protected AccountDetailsDao accountDetailsDao;
    @Inject
    protected UserDetailsDao userDetailsDao;
    @Inject
    protected DomainDao domainDao;
    @Inject
    protected RoleDao roleDao;
    @Inject
    protected SecurityGroupManager securityGroupManager;

    /** Clients by the authorities they trust: a provider's own, or, under the empty key, the CA certificates of the JDK. */
    private final Map<String, OidcClient> clients = new ConcurrentHashMap<>();
    private ScheduledExecutorService executor;

    protected OidcClient client(OidcProviderVO provider) throws OidcLoginException {
        String authorities = StringUtils.defaultString(provider.getCaCertificate());
        OidcClient client = clients.get(authorities);
        if (client == null) {
            HttpClient http;
            try {
                http = OidcClient.httpClient(StringUtils.trimToNull(authorities));
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new OidcLoginException(OidcLoginError.UNAVAILABLE, String.format("the trusted authorities of OpenID provider %s: %s", provider.getName(),
                        e.getMessage()));
            }
            client = clients.computeIfAbsent(authorities, k -> new OidcClient(http, Clock.systemUTC()));
        }
        return client;
    }

    /** Drops the cached discovery documents and key sets, so that the next sign-in at each provider fetches them. */
    void forgetProviderMetadata() {
        clients.clear();
    }

    protected Instant now() {
        return Instant.now();
    }

    /** Records a change of an account's state as an event of that account, as the account service's events are. */
    protected void recordAccountEvent(AccountVO account, String type, String description) {
        ActionEventUtils.onActionEvent(User.UID_SYSTEM, account.getId(), account.getDomainId(), type, description, account.getId(),
                ApiCommandResourceType.Account.toString());
    }

    protected <T> T inTransaction(Supplier<T> work) {
        return Transaction.execute((TransactionCallback<T>) status -> work.get());
    }

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        executor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("OidcAuth"));
        return true;
    }

    @Override
    public boolean start() {
        accountManager.addDisableReasonDetail(DISABLED_BY_DETAIL);
        accountManager.addDisableReasonDetail(GATE_DISABLED_BY);
        accountManager.addPasswordSignInRefusal(this::awaitsAdoption);
        reconcileRunner(executor).start();
        if (ReconcileInterval.value() > 0) {
            executor.schedule(new ReconcileTask(), FIRST_RECONCILE_DELAY_SECONDS, TimeUnit.SECONDS);
        }
        executor.scheduleWithFixedDelay(new PurgeTask(), PURGE_INTERVAL_SECONDS, PURGE_INTERVAL_SECONDS, TimeUnit.SECONDS);
        return true;
    }

    @Override
    public boolean stop() {
        if (executor != null) {
            executor.shutdown();
        }
        return true;
    }

    @Override
    public List<Class<?>> getAuthCommands() {
        List<Class<?>> commands = new ArrayList<>();
        commands.add(OidcLoginCmd.class);
        commands.add(OidcCallbackCmd.class);
        commands.add(ListOidcProvidersCmd.class);
        return commands;
    }

    @Override
    public List<Class<?>> getCommands() {
        List<Class<?>> commands = new ArrayList<>();
        commands.add(CreateOidcProviderCmd.class);
        commands.add(UpdateOidcProviderCmd.class);
        commands.add(DeleteOidcProviderCmd.class);
        return commands;
    }

    /** Records who changed which provider, as the events of the account service do. */
    protected void recordEvent(String type, String description) {
        CallContext context = CallContext.current();
        ActionEventUtils.onActionEvent(context.getCallingUserId(), context.getCallingAccountId(), context.getCallingAccount().getDomainId(), type,
                description, null, null);
    }

    // ---------------------------------------------------------------- providers

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    private static String optional(String value) {
        return StringUtils.trimToNull(value);
    }

    @Override
    public OidcProviderVO createProvider(CreateOidcProviderCmd cmd) {
        String name = StringUtils.trimToEmpty(cmd.getName());
        if (!PROVIDER_NAME.matcher(name).matches()) {
            throw new InvalidParameterValueException("A provider name is lowercase letters and digits, starting with a letter, at most 64 characters");
        }
        if (providerDao.findByName(name) != null) {
            throw new InvalidParameterValueException(String.format("An OpenID provider named %s exists", name));
        }
        OidcProviderVO provider = new OidcProviderVO();
        provider.setName(name);
        provider.setLabel(trimmed(cmd.getLabel()));
        provider.setIssuer(trimmed(cmd.getIssuer()));
        provider.setCaCertificate(optional(cmd.getCaCertificate()));
        provider.setClientId(trimmed(cmd.getClientId()));
        provider.setClientSecret(trimmed(cmd.getClientSecret()));
        provider.setScopes(cmd.getScopes() == null ? OidcProviderVO.DEFAULT_SCOPES : trimmed(cmd.getScopes()));
        provider.setAuthorizeParams(optional(cmd.getAuthorizeParams()));
        provider.setUsernameClaim(trimmed(cmd.getUsernameClaim()));
        provider.setLowercaseUsername(Boolean.TRUE.equals(cmd.getLowercaseUsername()));
        provider.setUsernamePattern(trimmed(cmd.getUsernamePattern()));
        provider.setRequireVerifiedEmail(Boolean.TRUE.equals(cmd.getRequireVerifiedEmail()));
        provider.setAdmission(trimmed(cmd.getAdmission()));
        provider.setRefusal(optional(cmd.getRefusal()));
        provider.setJit(!Boolean.FALSE.equals(cmd.getJit()));
        provider.setEndSessionUrl(optional(cmd.getEndSessionUrl()));
        provider.setEndSessionLabel(optional(cmd.getEndSessionLabel()));
        provider.setDomainId(cmd.getDomainId() == null ? 0L : cmd.getDomainId());
        provider.setRoleId(cmd.getRoleId() == null ? 0L : cmd.getRoleId());
        provider.setMembershipSource(optional(cmd.getMembershipSource()));
        provider.setMembershipSecret(optional(cmd.getMembershipSecret()));
        provider.setEnabled(!Boolean.FALSE.equals(cmd.getEnabled()));
        validate(provider);
        checkCallerMayConfigure(provider);
        OidcProviderVO created = providerDao.persist(provider);
        recordEvent(EventTypes.EVENT_OIDC_PROVIDER_CREATE, String.format("Created OpenID provider %s", name));
        return created;
    }

    private static <T> void set(List<String> applied, String parameter, T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
            applied.add(parameter);
        }
    }

    /** @return the parameters the command sets */
    private static List<String> apply(UpdateOidcProviderCmd cmd, OidcProviderVO provider) {
        List<String> applied = new ArrayList<>();
        set(applied, ApiConstants.LABEL, cmd.getLabel(), v -> provider.setLabel(trimmed(v)));
        set(applied, OidcApiConstants.ISSUER, cmd.getIssuer(), v -> provider.setIssuer(trimmed(v)));
        set(applied, OidcApiConstants.CA_CERTIFICATE, cmd.getCaCertificate(), v -> provider.setCaCertificate(optional(v)));
        set(applied, ApiConstants.CLIENT_ID, cmd.getClientId(), v -> provider.setClientId(trimmed(v)));
        set(applied, OidcApiConstants.CLIENT_SECRET, cmd.getClientSecret(), v -> provider.setClientSecret(trimmed(v)));
        set(applied, OidcApiConstants.SCOPES, cmd.getScopes(), v -> provider.setScopes(trimmed(v)));
        set(applied, OidcApiConstants.AUTHORIZE_PARAMS, cmd.getAuthorizeParams(), v -> provider.setAuthorizeParams(optional(v)));
        set(applied, OidcApiConstants.USERNAME_CLAIM, cmd.getUsernameClaim(), v -> provider.setUsernameClaim(trimmed(v)));
        set(applied, OidcApiConstants.LOWERCASE_USERNAME, cmd.getLowercaseUsername(), provider::setLowercaseUsername);
        set(applied, OidcApiConstants.USERNAME_PATTERN, cmd.getUsernamePattern(), v -> provider.setUsernamePattern(trimmed(v)));
        set(applied, OidcApiConstants.REQUIRE_VERIFIED_EMAIL, cmd.getRequireVerifiedEmail(), provider::setRequireVerifiedEmail);
        set(applied, OidcApiConstants.ADMISSION, cmd.getAdmission(), v -> provider.setAdmission(trimmed(v)));
        set(applied, OidcApiConstants.REFUSAL, cmd.getRefusal(), v -> provider.setRefusal(optional(v)));
        set(applied, OidcApiConstants.JIT, cmd.getJit(), provider::setJit);
        set(applied, OidcApiConstants.END_SESSION_URL, cmd.getEndSessionUrl(), v -> provider.setEndSessionUrl(optional(v)));
        set(applied, OidcApiConstants.END_SESSION_LABEL, cmd.getEndSessionLabel(), v -> provider.setEndSessionLabel(optional(v)));
        set(applied, ApiConstants.DOMAIN_ID, cmd.getDomainId(), provider::setDomainId);
        set(applied, ApiConstants.ROLE_ID, cmd.getRoleId(), provider::setRoleId);
        set(applied, OidcApiConstants.MEMBERSHIP_SOURCE, cmd.getMembershipSource(), v -> provider.setMembershipSource(optional(v)));
        set(applied, OidcApiConstants.MEMBERSHIP_SECRET, cmd.getMembershipSecret(), v -> provider.setMembershipSecret(optional(v)));
        set(applied, ApiConstants.ENABLED, cmd.getEnabled(), provider::setEnabled);
        return applied;
    }

    /**
     * Checks the command's values on a detached copy, then sets them on the entity the DAO loaded, whose update writes
     * the columns set on it.
     */
    @Override
    public OidcProviderVO updateProvider(UpdateOidcProviderCmd cmd) {
        OidcProviderVO stored = cmd.getId() == null ? null : providerDao.findById(cmd.getId());
        if (stored == null) {
            throw new InvalidParameterValueException("No such OpenID provider");
        }
        OidcProviderVO candidate = new OidcProviderVO(stored);
        List<String> applied = apply(cmd, candidate);
        validate(candidate);
        checkCallerMayConfigure(candidate);
        if (!candidate.getIssuer().equals(stored.getIssuer())) {
            List<OidcUserLinkVO> stale = new ArrayList<>();
            int users = linkedUsers(stored.getId(), stale);
            if (users > 0) {
                throw new InvalidParameterValueException(String.format("%d users sign in with OpenID provider %s; subjects of issuer %s are no "
                        + "subjects of another issuer", users, stored.getName(), stored.getIssuer()));
            }
            stale.forEach(link -> linkDao.expunge(link.getId()));
        }
        apply(cmd, stored);
        providerDao.update(stored.getId(), stored);
        forgetProviderMetadata();
        recordEvent(EventTypes.EVENT_OIDC_PROVIDER_UPDATE, String.format("Updated OpenID provider %s: %s", stored.getName(), String.join(", ", applied)));
        return providerDao.findById(stored.getId());
    }

    private static void required(String value, String what) {
        if (StringUtils.isBlank(value)) {
            throw new InvalidParameterValueException(String.format("An OpenID provider needs %s", what));
        }
    }

    private static void httpsUrl(String value, String what) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (Exception e) {
            throw new InvalidParameterValueException(String.format("%s %s is not a URL", what, value));
        }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getFragment() != null) {
            throw new InvalidParameterValueException(String.format("%s %s is not an https URL", what, value));
        }
    }

    protected void validate(OidcProviderVO provider) {
        required(provider.getLabel(), "a label");
        required(provider.getIssuer(), "an issuer");
        httpsUrl(provider.getIssuer(), "Issuer");
        if (URI.create(provider.getIssuer()).getQuery() != null) {
            throw new InvalidParameterValueException("An issuer identifier has no query");
        }
        if (provider.getCaCertificate() != null) {
            try {
                OidcClient.authorities(provider.getCaCertificate());
            } catch (IllegalArgumentException e) {
                throw new InvalidParameterValueException(String.format("The CA certificate of an OpenID provider is PEM certificates: %s", e.getMessage()));
            }
        }
        required(provider.getClientId(), "a client ID");
        required(provider.getClientSecret(), "a client secret");
        required(provider.getScopes(), "scopes");
        if (!List.of(provider.getScopes().split("\\s+")).contains("openid")) {
            throw new InvalidParameterValueException("The scopes of an OpenID provider include openid");
        }
        authorizeParams(provider.getAuthorizeParams());
        required(provider.getUsernameClaim(), "a username claim");
        required(provider.getUsernamePattern(), "a username pattern");
        try {
            Pattern.compile(provider.getUsernamePattern());
        } catch (PatternSyntaxException e) {
            throw new InvalidParameterValueException(String.format("The username pattern is not a regular expression: %s", e.getDescription()));
        }
        OidcAdmission.parse(provider.getAdmission());
        if (provider.getEndSessionUrl() != null) {
            httpsUrl(provider.getEndSessionUrl(), "End-session address");
        }
        if ((provider.getEndSessionUrl() == null) != (provider.getEndSessionLabel() == null)) {
            throw new InvalidParameterValueException("An end-session link needs both its address and its label");
        }
        DomainVO domain = domainDao.findById(provider.getDomainId());
        if (domain == null || domain.getRemoved() != null || domain.getState() != Domain.State.Active) {
            throw new InvalidParameterValueException("The domain of an OpenID provider must be an active domain");
        }
        RoleVO role = roleDao.findById(provider.getRoleId());
        if (role == null || role.getRoleType() == RoleType.Unknown) {
            throw new InvalidParameterValueException("The role of an OpenID provider must be a role of a known type");
        }
        if (role.getRoleType() == RoleType.Admin && provider.getDomainId() != Domain.ROOT_DOMAIN) {
            throw new InvalidParameterValueException("Accounts of an Admin-type role belong to the ROOT domain");
        }
        OidcMembershipSources.validate(provider.getMembershipSource(), provider.getMembershipSecret());
    }

    /**
     * The caller may configure only a provider whose accounts it could create itself: in a domain it may reach, with
     * a role that allows nothing the caller's role does not. Sign-in later creates them with no caller to check.
     */
    protected void checkCallerMayConfigure(OidcProviderVO provider) {
        Account caller = CallContext.current().getCallingAccount();
        accountManager.checkAccess(caller, domainDao.findById(provider.getDomainId()));
        RoleVO role = roleDao.findById(provider.getRoleId());
        AccountVO requested = new AccountVO(provider.getName(), provider.getDomainId(), null, role.getRoleType().getAccountType(), role.getId(),
                UUID.randomUUID().toString());
        accountManager.checkRoleEscalation(caller, requested);
    }

    /** @return the extra parameters of the authorization request */
    static Map<String, String> authorizeParams(String json) {
        Map<String, String> params = new LinkedHashMap<>();
        if (json == null) {
            return params;
        }
        JsonObject object;
        try {
            object = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new InvalidParameterValueException("The extra authorization parameters must be a JSON object");
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (PROTOCOL_PARAMETERS.contains(entry.getKey().toLowerCase())) {
                throw new InvalidParameterValueException(String.format("The authorization parameter %s is the protocol's", entry.getKey()));
            }
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
                throw new InvalidParameterValueException(String.format("The authorization parameter %s must be a string", entry.getKey()));
            }
            params.put(entry.getKey(), entry.getValue().getAsString());
        }
        return params;
    }

    /**
     * @param stale receives the links whose users were deleted
     * @return how many users that still exist are linked to subjects of the provider
     */
    private int linkedUsers(long providerId, List<OidcUserLinkVO> stale) {
        int users = 0;
        for (OidcUserLinkVO link : linkDao.listByProvider(providerId)) {
            UserVO user = userDao.findById(link.getUserId());
            if (user == null || user.getRemoved() != null) {
                stale.add(link);
            } else {
                users++;
            }
        }
        return users;
    }

    @Override
    public boolean deleteProvider(long id) {
        OidcProviderVO provider = providerDao.findById(id);
        if (provider == null) {
            throw new InvalidParameterValueException("No such OpenID provider");
        }
        List<OidcUserLinkVO> stale = new ArrayList<>();
        int users = linkedUsers(id, stale);
        if (users > 0) {
            throw new InvalidParameterValueException(String.format("%d users sign in with OpenID provider %s; disable it instead", users, provider.getName()));
        }
        stale.forEach(link -> linkDao.expunge(link.getId()));
        boolean deleted = providerDao.expunge(id);
        recordEvent(EventTypes.EVENT_OIDC_PROVIDER_DELETE, String.format("Deleted OpenID provider %s", provider.getName()));
        return deleted;
    }

    @Override
    public List<OidcProviderVO> listProviders(Long id, String name, boolean enabledOnly) {
        List<OidcProviderVO> providers = new ArrayList<>();
        if (id != null) {
            providers.add(providerDao.findById(id));
        } else if (name != null) {
            providers.add(providerDao.findByName(name));
        } else {
            providers.addAll(providerDao.listAll());
        }
        return providers.stream().filter(Objects::nonNull).filter(p -> !enabledOnly || p.isEnabled())
                .sorted(Comparator.comparingLong(OidcProviderVO::getId)).collect(Collectors.toList());
    }

    @Override
    public OidcProviderVO findProviderByUuid(String uuid) {
        return providerDao.findByUuid(uuid);
    }

    @Override
    public OidcProviderResponse createProviderResponse(OidcProviderVO provider, boolean details) {
        OidcProviderResponse response = new OidcProviderResponse();
        response.setId(provider.getUuid());
        response.setName(provider.getName());
        response.setLabel(provider.getLabel());
        response.setEndSessionUrl(provider.getEndSessionUrl());
        response.setEndSessionLabel(provider.getEndSessionLabel());
        response.setRefusal(provider.getRefusal());
        if (!details) {
            return response;
        }
        URI client = OidcAuthManager.clientUri();
        response.setIssuer(provider.getIssuer());
        response.setCaCertificate(provider.getCaCertificate());
        response.setClientId(provider.getClientId());
        response.setCallbackUrl(client == null ? null : client + CALLBACK_PATH + provider.getName());
        response.setScopes(provider.getScopes());
        response.setAuthorizeParams(provider.getAuthorizeParams());
        response.setUsernameClaim(provider.getUsernameClaim());
        response.setLowercaseUsername(provider.isLowercaseUsername());
        response.setUsernamePattern(provider.getUsernamePattern());
        response.setRequireVerifiedEmail(provider.isRequireVerifiedEmail());
        response.setAdmission(provider.getAdmission());
        response.setJit(provider.isJit());
        DomainVO domain = domainDao.findById(provider.getDomainId());
        if (domain != null) {
            response.setDomainId(domain.getUuid());
            response.setDomainPath(domain.getPath());
        }
        RoleVO role = roleDao.findById(provider.getRoleId());
        if (role != null) {
            response.setRoleId(role.getUuid());
            response.setRoleName(role.getName());
        }
        response.setMembershipSource(provider.getMembershipSource());
        response.setEnabled(provider.isEnabled());
        response.setCreated(provider.getCreated());
        return response;
    }

    // ---------------------------------------------------------------- sign-in

    static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new CloudRuntimeException("SHA-256 is not available", e);
        }
    }

    static String sha256Hex(String value) {
        StringBuilder hex = new StringBuilder();
        for (byte b : sha256(value)) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static long loginTimeout() {
        return Math.max(1, LoginTimeout.value());
    }

    private OidcProviderVO usableProvider(String name) throws OidcLoginException {
        OidcProviderVO provider = name == null ? null : providerDao.findByName(name);
        if (provider == null || !provider.isEnabled()) {
            throw new OidcLoginException(OidcLoginError.UNAVAILABLE, String.format("no enabled OpenID provider %s", name));
        }
        return provider;
    }

    private static URI configuredClient() throws OidcLoginException {
        URI client = OidcAuthManager.clientUri();
        if (client == null) {
            throw new OidcLoginException(OidcLoginError.UNAVAILABLE, String.format("%s is not set", ClientUrl.key()));
        }
        return client;
    }

    /**
     * Starts a sign-in. Nothing is kept for it but a row that expires with {@link OidcAuthManager#LoginTimeout} and is
     * purged periodically; the browser carries the binding.
     */
    @Override
    public OidcLoginStart startLogin(String providerName) throws OidcLoginException {
        OidcProviderVO provider = usableProvider(providerName);
        URI client = configuredClient();
        OidcProviderMetadata metadata = client(provider).metadata(provider.getIssuer());
        String state = random();
        String nonce = random();
        String verifier = random();
        String binding = random();
        String redirectUri = client + CALLBACK_PATH + provider.getName();
        loginRequestDao.persist(new OidcLoginRequestVO(provider.getId(), sha256Hex(state), nonce, verifier, sha256Hex(binding), redirectUri, Date.from(now())));
        Map<String, String> params = new LinkedHashMap<>(authorizeParams(provider.getAuthorizeParams()));
        params.put("response_type", "code");
        params.put("client_id", provider.getClientId());
        params.put("redirect_uri", redirectUri);
        params.put("scope", provider.getScopes());
        params.put("state", state);
        params.put("nonce", nonce);
        params.put("code_challenge", Base64.getUrlEncoder().withoutPadding().encodeToString(sha256Ascii(verifier)));
        params.put("code_challenge_method", "S256");
        String query = params.entrySet().stream().map(e -> OidcClient.formEncode(e.getKey()) + "=" + OidcClient.formEncode(e.getValue()))
                .collect(Collectors.joining("&"));
        String endpoint = metadata.getAuthorizationEndpoint();
        return new OidcLoginStart(endpoint + (endpoint.contains("?") ? "&" : "?") + query, binding);
    }

    void purgeExpiredLoginRequests() {
        int purged = loginRequestDao.removeCreatedBefore(Date.from(now().minusSeconds(loginTimeout())));
        if (purged > 0) {
            logger.debug("Purged {} OpenID sign-ins that were not finished in time", purged);
        }
    }

    final class PurgeTask extends ManagedContextRunnable {
        @Override
        protected void runInContext() {
            try {
                purgeExpiredLoginRequests();
            } catch (RuntimeException e) {
                logger.warn("Purging unfinished OpenID sign-ins failed: {}", e.getMessage());
            }
        }
    }

    private static byte[] sha256Ascii(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new CloudRuntimeException("SHA-256 is not available", e);
        }
    }

    /** @return a value taken from a request, cut short and reduced to characters that cannot forge log lines */
    static String printable(String value) {
        String shown = value.replaceAll("[^\\w.:/@-]", "?");
        return shown.length() > 100 ? shown.substring(0, 100) + "..." : shown;
    }

    private static boolean sameHash(String value, String hash) {
        return value != null && MessageDigest.isEqual(sha256Hex(value).getBytes(StandardCharsets.US_ASCII), hash.getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public long completeLogin(String providerName, Map<String, String> answer, String binding) throws OidcLoginException {
        OidcProviderVO provider = usableProvider(providerName);
        configuredClient();
        String state = answer.get(OidcApiConstants.STATE);
        OidcLoginRequestVO request = StringUtils.isEmpty(state) ? null : loginRequestDao.findByStateHash(sha256Hex(state));
        if (request == null || !loginRequestDao.consume(request.getId())) {
            throw new OidcLoginException(OidcLoginError.EXPIRED, "the answer matches no pending sign-in");
        }
        if (request.getProviderId() != provider.getId()) {
            throw new OidcLoginException(OidcLoginError.EXPIRED, "the answer belongs to a sign-in at another provider");
        }
        if (!sameHash(binding, request.getBindingHash())) {
            throw new OidcLoginException(OidcLoginError.EXPIRED, "the answer came to another browser than the one that started the sign-in");
        }
        if (request.getCreated().toInstant().plusSeconds(loginTimeout()).isBefore(now())) {
            throw new OidcLoginException(OidcLoginError.EXPIRED, "the answer came too late");
        }
        if (answer.get(OidcApiConstants.ERROR) != null) {
            throw new OidcLoginException(OidcLoginError.DENIED, String.format("the provider answered %s", printable(answer.get(OidcApiConstants.ERROR))));
        }
        OidcClient client = client(provider);
        OidcProviderMetadata metadata = client.metadata(provider.getIssuer());
        String iss = answer.get(OidcApiConstants.ISS);
        if (iss != null ? !iss.equals(provider.getIssuer()) : metadata.isIssParameterSupported()) {
            throw new OidcLoginException(OidcLoginError.FAILED, String.format("the answer names issuer %s", iss == null ? null : printable(iss)));
        }
        String code = answer.get(OidcApiConstants.CODE);
        if (StringUtils.isEmpty(code)) {
            throw new OidcLoginException(OidcLoginError.FAILED, "the answer has no authorization code");
        }
        JsonObject tokens = client.redeem(metadata, provider.getClientId(), provider.getClientSecret(), code, request.getRedirectUri(), request.getPkceVerifier());
        JsonObject claims = IdTokenVerifier.verify(OidcClient.string(tokens, "id_token"), refresh -> client.keys(metadata, refresh), provider.getIssuer(),
                provider.getClientId(), request.getNonce(), now());
        String accessToken = OidcClient.string(tokens, "access_token");
        if (metadata.getUserinfoEndpoint() != null && accessToken != null) {
            JsonObject info = client.userinfo(metadata, accessToken);
            if (!claims.get("sub").getAsString().equals(OidcClient.string(info, "sub"))) {
                throw new OidcLoginException(OidcLoginError.FAILED, "the userinfo response is of another subject");
            }
            for (Map.Entry<String, JsonElement> claim : info.entrySet()) {
                if (!TOKEN_CLAIMS.contains(claim.getKey())) {
                    claims.add(claim.getKey(), claim.getValue());
                }
            }
        }
        OidcAdmission admission;
        try {
            admission = OidcAdmission.parse(provider.getAdmission());
        } catch (InvalidParameterValueException e) {
            throw new OidcLoginException(OidcLoginError.UNAVAILABLE, String.format("admission rule of %s: %s", provider.getName(), e.getMessage()));
        }
        OidcIdentity identity = OidcIdentity.from(provider, admission, claims);
        boolean admitted = admission.admits(identity.getGroups());
        CallContext.register(accountManager.getSystemUser(), accountManager.getSystemAccount());
        try {
            return signIn(provider, identity, admitted);
        } finally {
            CallContext.unregister();
        }
    }

    private static String accountLock(long accountId) {
        return "oidc.account." + accountId;
    }

    /**
     * Finds, adopts or creates the person's account under the lock of its name, then, under the lock of the account
     * that sign-ins and the periodic check share, applies the admission decision to the account as it is now and
     * brings it in line with the provider.
     */
    private long signIn(OidcProviderVO provider, OidcIdentity identity, boolean admitted) throws OidcLoginException {
        String nameLock = "oidc.name." + sha256Hex(provider.getDomainId() + "/" + identity.getUsername()).substring(0, 40);
        UserVO resolved = underLock(nameLock, () -> resolve(provider, identity, admitted));
        if (resolved == null) {
            throw new OidcLoginException(OidcLoginError.NOT_ADMITTED, String.format("%s is not admitted by %s", identity.getUsername(), provider.getName()));
        }
        return underLock(accountLock(resolved.getAccountId()), () -> {
            AccountVO account = accountDao.findById(resolved.getAccountId());
            UserVO user = userDao.findById(resolved.getId());
            if (!admitted) {
                disable(provider, account);
                throw new OidcLoginException(OidcLoginError.NOT_ADMITTED, String.format("%s is no longer admitted by %s", identity.getUsername(),
                        provider.getName()));
            }
            bringInLine(provider, identity, user, account);
            return user.getId();
        });
    }

    /**
     * @return the linked user, an adopted marked account, a new account, or null for a person who is not admitted and
     *         has no account of the provider
     */
    private UserVO resolve(OidcProviderVO provider, OidcIdentity identity, boolean admitted) throws OidcLoginException {
        OidcUserLinkVO link = linkDao.findByProviderAndSubject(provider.getId(), identity.getSubject());
        if (link != null) {
            UserVO user = userDao.findById(link.getUserId());
            AccountVO account = user == null ? null : accountDao.findById(user.getAccountId());
            if (user != null && user.getRemoved() == null && account != null && account.getRemoved() == null) {
                return user;
            }
            linkDao.expunge(link.getId());
            logger.info("OpenID provider {}: the account of subject {} was deleted; a new one replaces it", provider.getName(), identity.getSubject());
        }
        DomainVO domain = domainDao.findById(provider.getDomainId());
        if (domain == null || domain.getRemoved() != null || domain.getState() != Domain.State.Active) {
            throw new OidcLoginException(OidcLoginError.UNAVAILABLE, String.format("the domain of OpenID provider %s is not active", provider.getName()));
        }
        UserVO existing = userDao.getUserByName(identity.getUsername(), provider.getDomainId());
        if (existing != null && linkDao.findByUserId(existing.getId()) == null && isGateAccount(existing.getAccountId())) {
            return adopt(provider, identity, existing);
        }
        if (!admitted) {
            return null;
        }
        if (existing != null) {
            throw new OidcLoginException(OidcLoginError.CONFLICT, String.format("user %s exists in domain %s and is no account of %s", identity.getUsername(),
                    domain.getPath(), provider.getName()));
        }
        if (!provider.isJit()) {
            throw new OidcLoginException(OidcLoginError.NO_ACCOUNT, String.format("%s has no account and %s creates none", identity.getUsername(),
                    provider.getName()));
        }
        return create(provider, identity);
    }

    private boolean isGateAccount(long accountId) {
        AccountDetailVO mark = accountDetailsDao.findDetail(accountId, GATE_MARK);
        return mark != null && "true".equals(mark.getValue());
    }

    /**
     * A marked account is taken over at its owner's first sign-in through a provider. Once this server offers that
     * sign-in, its password is no way in any more, so a password attempt neither succeeds nor counts towards a lockout.
     */
    private boolean awaitsAdoption(UserAccount user) {
        return OidcAuthManager.clientUri() != null && isGateAccount(user.getAccountId());
    }

    /**
     * Takes over a marked account once: links it, ends its password sign-in, and turns the earlier service's disable
     * mark into this module's, so that such a disable is lifted as one of this module. The mark is turned over with
     * the account row locked, so that an enable, which removes both marks in its transaction, comes before or after.
     */
    private UserVO adopt(OidcProviderVO provider, OidcIdentity identity, UserVO user) {
        AccountVO account = accountDao.findById(user.getAccountId());
        Map<String, String> details = accountDetailsDao.findDetails(account.getId());
        linkDao.persist(new OidcUserLinkVO(provider.getId(), identity.getSubject(), user.getId()));
        user.setSource(User.Source.OAUTH2);
        userDao.update(user.getId(), user);
        userDetailsDao.removeDetail(user.getId(), UserDetailVO.PasswordChangeRequired);
        if (GATE_MARK.equals(details.get(GATE_DISABLED_BY))) {
            inTransaction(() -> {
                AccountVO locked = accountDao.lockRow(account.getId(), true);
                if (locked != null && locked.getState() != Account.State.ENABLED && disabledByUs(account.getId(), false)) {
                    accountDetailsDao.addDetail(account.getId(), DISABLED_BY_DETAIL, provider.getName(), false);
                }
                accountDetailsDao.removeDetail(account.getId(), GATE_DISABLED_BY);
                return null;
            });
        }
        accountDetailsDao.removeDetail(account.getId(), GATE_MARK);
        logger.info("OpenID provider {}: marked account {} adopted", provider.getName(), account.getAccountName());
        return user;
    }

    /**
     * Creates the account through the account service, so that everything that follows account creation, such as its
     * default security group and resource limits, applies to it too.
     */
    private UserVO create(OidcProviderVO provider, OidcIdentity identity) throws OidcLoginException {
        RoleVO role = roleDao.findById(provider.getRoleId());
        if (role == null) {
            throw new OidcLoginException(OidcLoginError.UNAVAILABLE, String.format("the role of OpenID provider %s does not exist", provider.getName()));
        }
        UserAccount created;
        try {
            created = accountManager.createUserAccount(identity.getUsername(), random(), identity.getFirstName(), identity.getLastName(), identity.getEmail(),
                    null, identity.getUsername(), role.getRoleType().getAccountType(), role.getId(), provider.getDomainId(), null, null, null, null,
                    User.Source.OAUTH2);
        } catch (InvalidParameterValueException e) {
            throw new OidcLoginException(OidcLoginError.CONFLICT, String.format("account %s: %s", identity.getUsername(), e.getMessage()), e);
        }
        linkDao.persist(new OidcUserLinkVO(provider.getId(), identity.getSubject(), created.getId()));
        logger.info("OpenID provider {}: account {} created", provider.getName(), identity.getUsername());
        return userDao.findById(created.getId());
    }

    /**
     * @param takenOver false for a marked account no sign-in took over yet, whose disable by the earlier service counts
     * @return whether this module, or for such an account the earlier service, disabled the account
     */
    private boolean disabledByUs(long accountId, boolean takenOver) {
        if (accountDetailsDao.findDetail(accountId, DISABLED_BY_DETAIL) != null) {
            return true;
        }
        AccountDetailVO gate = takenOver ? null : accountDetailsDao.findDetail(accountId, GATE_DISABLED_BY);
        return gate != null && GATE_MARK.equals(gate.getValue());
    }

    private void removeDisableMarks(long accountId) {
        accountDetailsDao.removeDetail(accountId, DISABLED_BY_DETAIL);
        accountDetailsDao.removeDetail(accountId, GATE_DISABLED_BY, GATE_MARK);
    }

    /**
     * Disables an enabled account whose person no longer qualifies and marks it, so that only this module enables it
     * again. State and mark change in one transaction with the account row locked; an enable removes the mark in its
     * own transaction, so the two never interleave. Sessions and keys stop at once; the resources stop in a job.
     * Runs under the account's lock.
     */
    private void disable(OidcProviderVO provider, AccountVO account) {
        AccountVO disabled = inTransaction(() -> {
            AccountVO locked = accountDao.lockRow(account.getId(), true);
            if (locked == null || locked.getState() != Account.State.ENABLED) {
                return null;
            }
            locked.setState(Account.State.DISABLED);
            locked.setNeedsCleanup(true);
            accountDao.update(locked.getId(), locked);
            accountDetailsDao.addDetail(locked.getId(), DISABLED_BY_DETAIL, provider.getName(), false);
            return locked;
        });
        if (disabled != null) {
            logger.info("OpenID provider {}: account {} disabled, its person no longer qualifies", provider.getName(), disabled.getAccountName());
            recordAccountEvent(disabled, EventTypes.EVENT_ACCOUNT_DISABLE, String.format("Disabled account %s: its person no longer qualifies for "
                    + "OpenID provider %s", disabled.getAccountName(), provider.getName()));
            executor.execute(new ResourceStop(disabled.getId()));
        }
    }

    /** Stops the resources of an account this module disabled, as the account service does for a disable. */
    final class ResourceStop extends ManagedContextRunnable {
        private final long accountId;

        ResourceStop(long accountId) {
            this.accountId = accountId;
        }

        @Override
        protected void runInContext() {
            stopResources(accountId);
        }
    }

    /**
     * Stops the resources only while the disable this module decided holds: with the account row locked, the account
     * is still disabled with this module's mark and resources to stop. An enable since, by a sign-in or an
     * administrator, removed the mark, and nothing is done. The account service stops them without writing the state,
     * so an enable or a lock committed after this check is never undone; it costs at most the stops. What does not
     * stop is left to the account cleanup task.
     */
    void stopResources(long accountId) {
        CallContext.register(accountManager.getSystemUser(), accountManager.getSystemAccount());
        try {
            boolean ours = inTransaction(() -> {
                AccountVO account = accountDao.lockRow(accountId, true);
                return account != null && account.getRemoved() == null && account.getState() == Account.State.DISABLED && account.getNeedsCleanup()
                        && disabledByUs(accountId, true);
            });
            if (!ours) {
                logger.debug("Account {} is no longer one this module disabled with resources to stop", accountId);
            } else if (!accountManager.stopResourcesOfDisabledAccount(accountId)) {
                logger.warn("Not every resource of account {} stopped; the account cleanup task stops the rest", accountId);
            }
        } catch (RuntimeException e) {
            logger.warn("The resources of account {} were not stopped; the account cleanup task stops them: {}", accountId, e.getMessage());
        } finally {
            CallContext.unregister();
        }
    }

    /**
     * Enables an account this module disabled, if it still is: with the account row locked, its state and mark are
     * read again, and the account service enables it in the same transaction, removing the marks.
     */
    private void enable(OidcProviderVO provider, long accountId, boolean takenOver) {
        AccountVO enabled = inTransaction(() -> {
            AccountVO locked = accountDao.lockRow(accountId, true);
            if (locked == null || locked.getState() != Account.State.DISABLED || !disabledByUs(accountId, takenOver)) {
                return null;
            }
            return accountManager.enableAccount(accountId) ? locked : null;
        });
        if (enabled != null) {
            logger.info("OpenID provider {}: account {} enabled again", provider.getName(), enabled.getAccountName());
            recordAccountEvent(enabled, EventTypes.EVENT_ACCOUNT_ENABLE, String.format("Enabled account %s: its person qualifies for OpenID provider %s "
                    + "again", enabled.getAccountName(), provider.getName()));
        }
    }

    private void bringInLine(OidcProviderVO provider, OidcIdentity identity, UserVO user, AccountVO account) throws OidcLoginException {
        if (account.getState() == Account.State.DISABLED && disabledByUs(account.getId(), true)) {
            enable(provider, account.getId(), true);
            account = accountDao.findById(account.getId());
        } else if (account.getState() == Account.State.ENABLED && disabledByUs(account.getId(), true)) {
            removeDisableMarks(account.getId());
        }
        if (account.getState() != Account.State.ENABLED) {
            throw new OidcLoginException(OidcLoginError.DISABLED, String.format("account %s is %s", account.getAccountName(), account.getState()));
        }
        if (user.getState() != Account.State.ENABLED) {
            throw new OidcLoginException(OidcLoginError.DISABLED, String.format("user %s is %s", user.getUsername(), user.getState()));
        }
        RoleVO role = roleDao.findById(provider.getRoleId());
        if (role == null) {
            throw new OidcLoginException(OidcLoginError.UNAVAILABLE, String.format("the role of OpenID provider %s does not exist", provider.getName()));
        }
        if (account.getRoleId() == null || account.getRoleId() != role.getId()) {
            if (role.getRoleType().getAccountType() != account.getType()) {
                throw new OidcLoginException(OidcLoginError.CONFLICT, String.format("account %s is of type %s, role %s of %s is not", account.getAccountName(),
                        account.getType(), role.getName(), provider.getName()));
            }
            account.setRoleId(role.getId());
            accountDao.update(account.getId(), account);
        }
        releaseEmail(account.getDomainId(), identity.getEmail(), user);
        boolean changed = false;
        if (!identity.getEmail().equals(user.getEmail())) {
            user.setEmail(identity.getEmail());
            changed = true;
        }
        if (!identity.getFirstName().equals(user.getFirstname()) || !identity.getLastName().equals(user.getLastname())) {
            user.setFirstname(identity.getFirstName());
            user.setLastname(identity.getLastName());
            changed = true;
        }
        if (changed) {
            userDao.update(user.getId(), user);
        }
        try {
            securityGroupManager.applyDefaultSecurityGroupRules(account.getId());
        } catch (RuntimeException e) {
            logger.warn("Default security group rules of account {} were not ensured: {}", account.getAccountName(), e.getMessage(), e);
        }
    }

    /**
     * Within its domain the provider is authoritative for addresses: another user of the domain still carrying the
     * address, whatever its letter case, holds a stale copy that would make a lookup by address ambiguous. It is
     * parked until that user signs in again; users of other domains are not touched.
     */
    private void releaseEmail(long domainId, String email, UserVO user) {
        for (UserAccountVO holder : userAccountDao.getUserAccountByEmail(email, domainId)) {
            if (holder.getId() == user.getId()) {
                continue;
            }
            UserVO other = userDao.findById(holder.getId());
            if (other != null) {
                other.setEmail(other.getUsername() + STALE_EMAIL_DOMAIN);
                userDao.update(other.getId(), other);
                logger.info("The stale e-mail address of user {} went to user {}", other.getUsername(), user.getUsername());
            }
        }
    }

    /**
     * Runs the task under the cluster-wide lock of the name, held by one thread of one management server at a time;
     * a thread that holds it may take it again.
     */
    protected <T> T underLock(String name, Callable<T> task) throws OidcLoginException {
        GlobalLock lock = GlobalLock.getInternLock(name);
        try {
            if (!lock.lock(LOCK_TIMEOUT_SECONDS)) {
                throw new OidcLoginException(OidcLoginError.FAILED, String.format("%s is held by another sign-in or check", name));
            }
            try {
                return task.call();
            } finally {
                lock.unlock();
            }
        } catch (OidcLoginException e) {
            throw e;
        } catch (Exception e) {
            throw new OidcLoginException(OidcLoginError.FAILED, e.getMessage(), e);
        } finally {
            lock.releaseRef();
        }
    }

    // ---------------------------------------------------------------- periodic reconcile

    protected OidcMembershipSource membershipSource(OidcProviderVO provider) {
        try {
            return OidcMembershipSources.create(provider.getMembershipSource(), provider.getMembershipSecret(), OidcClient.httpClient(null),
                    Clock.systemUTC());
        } catch (IllegalArgumentException e) {
            logger.warn("The membership source of OpenID provider {} is not usable: {}", provider.getName(), e.getMessage());
            return null;
        }
    }

    /**
     * Checks the people of every provider with a membership source: the users linked to its subjects, known to the
     * source by their subject, and the marked accounts it would take over at their first sign-in, known by their
     * username.
     */
    @Override
    public void reconcile() {
        for (OidcProviderVO provider : providerDao.listAll()) {
            OidcMembershipSource source = membershipSource(provider);
            if (source == null) {
                logger.debug("OpenID provider {} has no membership source; its accounts change only at sign-in", provider.getName());
                continue;
            }
            OidcAdmission admission;
            try {
                admission = OidcAdmission.parse(provider.getAdmission());
            } catch (InvalidParameterValueException e) {
                logger.warn("The admission rule of OpenID provider {} is not usable: {}", provider.getName(), e.getMessage());
                continue;
            }
            for (OidcUserLinkVO link : linkDao.listByProvider(provider.getId())) {
                if (link.getSubject().startsWith(admission.getSubjectPrefix())) {
                    reconcile(provider, admission, source, link.getUserId(), link.getSubject().substring(admission.getSubjectPrefix().length()), true);
                }
            }
            for (UserVO user : untakenAccounts(provider)) {
                reconcile(provider, admission, source, user.getId(), user.getUsername(), false);
            }
        }
    }

    /** @return the users of marked accounts that the provider takes over at their person's first sign-in */
    private List<UserVO> untakenAccounts(OidcProviderVO provider) {
        Pattern usernames = Pattern.compile(provider.getUsernamePattern());
        List<UserVO> users = new ArrayList<>();
        for (Long accountId : accountDetailsDao.findResourceIdsByNameAndValueIn(GATE_MARK, new Object[] {"true"})) {
            AccountVO account = accountDao.findById(accountId);
            if (account == null || account.getRemoved() != null || account.getDomainId() != provider.getDomainId() || !isGateAccount(accountId)) {
                continue;
            }
            for (UserVO user : userDao.listByAccount(accountId)) {
                if (user.getRemoved() == null && usernames.matcher(user.getUsername()).matches() && linkDao.findByUserId(user.getId()) == null) {
                    users.add(user);
                }
            }
        }
        return users;
    }

    /** Applies the source's answer about one person to their account, as their sign-in would. */
    private void reconcile(OidcProviderVO provider, OidcAdmission admission, OidcMembershipSource source, long userId, String member, boolean takenOver) {
        try {
            UserVO user = userDao.findById(userId);
            if (user == null || user.getRemoved() != null) {
                return;
            }
            Boolean admitted = admission.admits(group -> source.isMember(member, group));
            if (admitted == null) {
                return;
            }
            underLock(accountLock(user.getAccountId()), () -> {
                AccountVO account = accountDao.findById(user.getAccountId());
                if (account == null || account.getRemoved() != null) {
                    return null;
                }
                if (!admitted) {
                    disable(provider, account);
                } else if (account.getState() == Account.State.DISABLED && disabledByUs(account.getId(), takenOver)) {
                    enable(provider, account.getId(), takenOver);
                } else if (account.getState() == Account.State.ENABLED && disabledByUs(account.getId(), takenOver)) {
                    removeDisableMarks(account.getId());
                }
                return null;
            });
        } catch (OidcLoginException | RuntimeException e) {
            logger.warn("Checking the membership of user {} of OpenID provider {} failed: {}", userId, provider.getName(), e.getMessage());
        }
    }

    final class ReconcileTask extends ManagedContextRunnable {
        @Override
        protected void runInContext() {
            runReconcile();
        }
    }

    /** Runs {@link #reconcile()} on one management server at a time. */
    protected void runReconcile() {
        GlobalLock lock = GlobalLock.getInternLock(RECONCILE_LOCK);
        try {
            if (!lock.lock(0)) {
                logger.debug("Another management server checks the OpenID membership sources");
                return;
            }
            CallContext.register(accountManager.getSystemUser(), accountManager.getSystemAccount());
            try {
                reconcile();
            } finally {
                CallContext.unregister();
                lock.unlock();
            }
        } finally {
            lock.releaseRef();
        }
    }

    protected ConfigKeyScheduledExecutionWrapper reconcileRunner(ScheduledExecutorService executor) {
        return new ConfigKeyScheduledExecutionWrapper(executor, new ReconcileTask(), ReconcileInterval, TimeUnit.SECONDS);
    }

    @Override
    public String getConfigComponentName() {
        return OidcAuthManager.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {ClientUrl, LoginTimeout, ReconcileInterval};
    }

    /**
     * Where sign-in sends people and codes, and how soon a person who left the provider's groups loses the account,
     * govern access; how long a started sign-in may take does not.
     */
    @Override
    public String[] getAccessControlConfigKeyNames() {
        return new String[] {ClientUrl.key(), ReconcileInterval.key()};
    }
}
