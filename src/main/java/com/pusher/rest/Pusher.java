package com.pusher.rest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.List;

import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.pusher.rest.util.Prerequisites;

public class Pusher {
    private static final Gson BODY_SERIALISER = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create();

    private final String appId;
    private final String key;
    private final String secret;

    private String host = "api.pusherapp.com";
    private String scheme = "http";
    private int requestTimeout = 4000; // milliseconds

    private CloseableHttpClient client;
    private Gson dataMarshaller;

    /**
     * Construct an instance of the Pusher object through which you may interact with the Pusher API.
     *
     * The parameters to use are found on your dashboard at https://app.pusher.com and are specific per App.
     *
     * @param appId The ID of the App you will to interact with.
     * @param key The App Key, the same key you give to websocket clients to identify your app when they connect to Pusher.
     * @param secret The App Secret. Used to sign requests to the API, this should be treated as sensitive and not distributed.
     */
    public Pusher(final String appId, final String key, final String secret) {
        Prerequisites.nonNull("appId", appId);
        Prerequisites.nonNull("key", key);
        Prerequisites.nonNull("secret", secret);
        Prerequisites.isValidSha256Key("secret", secret);

        this.appId = appId;
        this.key = key;
        this.secret = secret;

        this.configureHttpClient(defaultHttpClientBuilder());

        this.dataMarshaller = new Gson();
    }

    /**
     * Set the API endpoint host.
     *
     * For testing or specifying an alternative cluster.
     *
     * Default: api.pusherapp.com
     */
    public void setHost(final String host) {
        Prerequisites.nonNull("host", host);

        this.host = host;
    }

    /**
     * Set whether to use a secure connection to the API (SSL).
     *
     * Authentication is secure even without this option, requests cannot be faked or replayed with access
     * to their plain text, a secure connection is only required if the requests or responses contain
     * sensitive information.
     *
     * Default: false
     */
    public void setSecure(final boolean secure) {
        this.scheme = secure ? "https" : "http";
    }

    /**
     * Set the request timeout in milliseconds
     *
     * Default 4000
     */
    public void setRequestTimeout(final int requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    /**
     * Set the Gson instance used to marshall Objects passed to #trigger
     *
     * The library marshalls the objects provided to JSON using the Gson library
     * (see https://code.google.com/p/google-gson/ for more details). By providing an instance
     * here, you may exert control over the marshalling, for example choosing how Java property
     * names are mapped on to the field names in the JSON representation, allowing you to match
     * the expected scheme on the client side.
     */
    public void setGsonSerialiser(final Gson gson) {
        this.dataMarshaller = gson;
    }

    /**
     * Returns an HttpClientBuilder with the settings used by default applied. You may apply
     * further configuration (for example an HTTP proxy), override existing configuration
     * (for example, the connection manager which handles connection pooling for reuse) and
     * then call {@link #configureHttpClient(HttpClientBuilder)} to have this configuration
     * applied to all subsequent calls.
     */
    public static HttpClientBuilder defaultHttpClientBuilder() {
        return HttpClientBuilder.create()
                .setConnectionManager(new PoolingHttpClientConnectionManager())
                .setConnectionReuseStrategy(new DefaultConnectionReuseStrategy())
                .setKeepAliveStrategy(new DefaultConnectionKeepAliveStrategy())
                .disableRedirectHandling();
    }

    /**
     * Configure the HttpClient instance which will be used for making calls to the Pusher API.
     *
     * This method allows almost complete control over all aspects of the HTTP client, including
     *  - proxy host
     *  - connection pooling and reuse strategies
     *  - automatic retry and backoff strategies
     *
     * It is *strongly* recommended that you take the value of {@link #defaultHttpClientBuilder()}
     * as a base, apply your custom config to that and then pass the builder in here, to ensure
     * that sensible defaults for configuration areas you are not setting explicitly are retained.
     *
     * e.g.
     * <code>
     *     pusher.configureHttpClient(
     *         Pusher.defaultHttpClientBuilder()
     *               .setProxy(new HttpHost("proxy.example.com"))
     *               .disableAutomaticRetries()
     *     );
     * </code>
     */
    public void configureHttpClient(final HttpClientBuilder builder) {
        try {
            if (client != null) client.close();
        }
        catch (IOException e) {
            // Not a lot useful we can do here
        }

        this.client = builder.build();
    }

    /**
     * Publish a message to a single channel.
     *
     * The message data should be a POJO, which will be serialised to JSON for submission.
     * Use {@link #setGsonSerialiser(Gson)} to control the serialisation
     *
     * Note that if you do not wish to create classes specifically for the purpose of specifying
     * the message payload, use Map<String, Object>. These maps will nest just fine.
     */
    public Result trigger(final String channel, final String eventName, final Object data) {
        return trigger(channel, eventName, data, null);
    }

    /**
     * Publish identical messages to multiple channels.
     */
    public Result trigger(final List<String> channels, final String eventName, final Object data) {
        return trigger(channels, eventName, data, null);
    }

    /**
     * Publish a message to a single channel, excluding the specified socketId from receiving the message.
     */
    public Result trigger(final String channel, final String eventName, final Object data, final String socketId) {
        return trigger(Collections.singletonList(channel), eventName, data, socketId);
    }

    /**
     * Publish identical messages to multiple channels, excluding the specified socketId from receiving the message.
     */
    public Result trigger(final List<String> channels, final String eventName, final Object data, final String socketId) {
        Prerequisites.nonNull("channels", channels);
        Prerequisites.nonNull("eventName", eventName);
        Prerequisites.nonNull("data", data);
        Prerequisites.maxLength("channels", 10, channels);
        Prerequisites.noNullMembers("channels", channels);

        final String path = "/apps/" + appId + "/events";
        final String body = BODY_SERIALISER.toJson(new TriggerData(channels, eventName, serialise(data), socketId));

        return httpCall(path, body);
    }

    Result httpCall(final String path, final String body) {
        final URI uri = SignedRequest.uri("POST", scheme, host, path, body, key, secret, Collections.<String, String>emptyMap());

        final StringEntity bodyEntity = new StringEntity(body, "UTF-8");
        bodyEntity.setContentType("application/json");

        final HttpPost request = new HttpPost(uri);
        request.setEntity(bodyEntity);

        final RequestConfig config = RequestConfig.custom()
                .setSocketTimeout(requestTimeout)
                .setConnectionRequestTimeout(requestTimeout)
                .setConnectTimeout(requestTimeout)
                .build();
        request.setConfig(config);

        try {
            final HttpResponse response = client.execute(request);

            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            response.getEntity().writeTo(baos);
            final String responseBody = new String(baos.toByteArray(), "UTF-8");

            return Result.fromHttpCode(response.getStatusLine().getStatusCode(), responseBody);
        }
        catch (final IOException e) {
            return Result.fromException(e);
        }
    }

    /**
     * This method provides an override point if the default Gson based serialisation is absolutely
     * unsuitable for your use case, even with customisation of the Gson instance doing the serialisation.
     */
    protected String serialise(final Object data) {
        return dataMarshaller.toJson(data);
    }
}
