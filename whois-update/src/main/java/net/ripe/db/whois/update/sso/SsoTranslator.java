package net.ripe.db.whois.update.sso;

import com.google.common.base.Splitter;
import com.google.common.collect.Maps;
import net.ripe.db.whois.common.rpsl.AttributeType;
import net.ripe.db.whois.common.rpsl.ObjectType;
import net.ripe.db.whois.common.rpsl.RpslAttribute;
import net.ripe.db.whois.common.rpsl.RpslObject;
import net.ripe.db.whois.common.rpsl.RpslObjectBuilder;
import net.ripe.db.whois.update.domain.Update;
import net.ripe.db.whois.update.domain.UpdateContext;
import org.glassfish.jersey.client.filter.HttpBasicAuthFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.ws.rs.NotFoundException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import java.util.Iterator;
import java.util.Map;

@Component
public class SsoTranslator {
    public static final Splitter SPACE_SPLITTER = Splitter.on(' ');
    private final String restUrl;
    private final Client client;

    @Autowired
    public SsoTranslator(@Value("${rest.crowd.url}") final String translatorUrl,
                         @Value("${rest.crowd.username}") final String crowdAuthUser,
                         @Value("${rest.crowd.password}") final String crowdAuthPassword) {
        this.restUrl = translatorUrl;
        client = ClientBuilder.newBuilder().register(new HttpBasicAuthFilter(crowdAuthUser, crowdAuthPassword)).build();
    }

    public String getUuidForUsername(final String username) {
        final String url = String.format(
                "%s/rest/usermanagement/latest/user/attribute?username=%s",
                restUrl,
                username);

        String response = "";
        try {
            response = client.target(url).request().get(String.class);
        } catch (NotFoundException e) {
            throw new IllegalArgumentException("Unknown RIPE Access user: " + username);
        }

        return extractUUID(response);
    }

    public String getUsernameForUuid(final String uuid) {
        final String url = String.format(
                "%scrowd/rest/sso/latest/uuid=%s",
                restUrl,
                uuid);

        final String response = "";
        try {
            client.target(url).request().get(String.class);
        } catch (NotFoundException e) {
            throw new IllegalArgumentException("Unknown RIPE Access uuid: " + uuid);
        }

        return extractUsername(response);
    }

//    TODO
    private String extractUUID(final String response) {
        final StringBuilder builder = new StringBuilder(response);
        int pre = builder.indexOf("<value>", builder.indexOf("name=uuid"));
        int post = builder.indexOf("</value>", pre);
        return builder.substring(pre + "<value>".length(), post);
    }

//    TODO
    private String extractUsername(final String response) {
        int pre = response.indexOf("name=");
        int post = response.indexOf("\">", pre);
        return response.substring(pre + "name=\"".length(), post);
    }

    public void populate(final Update update, final UpdateContext updateContext) {
        final RpslObject submittedObject = update.getSubmittedObject();
        if (!ObjectType.MNTNER.equals(submittedObject.getType())) {
            return;
        }

        for (final RpslAttribute auth : submittedObject.findAttributes(AttributeType.AUTH)) {
            final Iterator<String> authIterator = SPACE_SPLITTER.split(auth.getCleanValue()).iterator();
            final String passwordType = authIterator.next();
            if (passwordType.equalsIgnoreCase("SSO")) {
                String username = authIterator.next();
                if (!updateContext.hasSsoTranslationResult(username)) {
                    updateContext.addSsoTranslationResult(username, getUuidForUsername(username));
                }
            }
        }
    }

    public RpslObject translateAuthToUuid(UpdateContext updateContext, RpslObject rpslObject) {
        return translateAuth(updateContext, rpslObject);
    }

    public RpslObject translateAuthToUsername(final UpdateContext updateContext, final RpslObject rpslObject) {
        return translateAuth(updateContext, rpslObject);
    }

    private RpslObject translateAuth(final UpdateContext updateContext, final RpslObject rpslObject) {
        if (!ObjectType.MNTNER.equals(rpslObject.getType())) {
            return rpslObject;
        }

        Map<RpslAttribute, RpslAttribute> replace = Maps.newHashMap();
        for (RpslAttribute auth : rpslObject.findAttributes(AttributeType.AUTH)) {
            final Iterator<String> authIterator = SPACE_SPLITTER.split(auth.getCleanValue()).iterator();
            final String passwordType = authIterator.next().toUpperCase();
            if (passwordType.equals("SSO")) {
                String token = authIterator.next();
                String authValue = "SSO " + updateContext.getSsoTranslationResult(token);
                replace.put(auth, new RpslAttribute(auth.getKey(), authValue));
            }
        }

        if (replace.isEmpty()) {
            return rpslObject;
        } else {
            return new RpslObjectBuilder(rpslObject).replaceAttributes(replace).get();
        }
    }
}