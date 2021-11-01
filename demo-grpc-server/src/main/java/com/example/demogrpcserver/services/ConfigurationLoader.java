package com.example.demogrpcserver.services;

import com.example.demogrpcserver.helpers.EnvoyHelpers;
import com.example.demogrpcserver.models.GroupConfigDto;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import io.envoyproxy.controlplane.cache.NodeGroup;
import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;
import lombok.Builder;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.SetUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ConfigurationLoader {

    /**
     * TODO
     * - Learn why ClusterLoadAssignment is useful and for what
     * - Implement configuration per Envoy node (not useful for me now)
     */

    public static final String DEFAULT_SECRET_NAME = "ads_secret_default_cert";

    private String defaultCertsPath = "/certs";


    // Limit to 10 versions
    // Use to rollback
    // String (version) -> DATA
    private TreeMap<String, SnapshotState> snapshotByVersion = new TreeMap<>();
    // Manage infinite rollback loop
    // String (TypeUrl) -> String (version) -> Integer (count)
    private Map<String, Map<String, Integer>> tryToRollbackCountByVersionByTypeUrl = new HashMap<>();


    // TODO - DEV ONLY that act as a DB
    private static final Set<Cluster> CLUSTERS_IN_DATABASE = new HashSet<>();
    private static final Set<Listener> LISTENERS_IN_DATABASE = new HashSet<>();
    private static final Set<RouteConfiguration> ROUTES_IN_DATABASE = new HashSet<>();
    private static final Set<Secret> SECRETS_IN_DATABASE = new HashSet<>();
    private static final Set<ClusterLoadAssignment> ENDPOINTS_IN_DATABASE = new HashSet<>(); // Not used ATM - Not learn why it is useful for now :/

    // Init "DB"
    static {

        ConfigurationLoader.LISTENERS_IN_DATABASE.add(
                EnvoyHelpers.createDynamicListener(
                        "listener_default_80",
                        "0.0.0.0",
                        80,
                        null
                )
        );

        ConfigurationLoader.LISTENERS_IN_DATABASE.add(
                EnvoyHelpers.createDynamicListener(
                        "ads_listener_default_443",
                        "0.0.0.0",
                        443,
                        ConfigurationLoader.createDataForDynamicListener443()
                )
        );

        ConfigurationLoader.LISTENERS_IN_DATABASE.add(
                EnvoyHelpers.createListener(
                        "ads_listener_whoami_test_01",
                        "0.0.0.0",
                        10005,
                        "whoami.ifenua-direct.pf",
                        "ads_whoami_cluster_test_01"
                )
        );

        ConfigurationLoader.LISTENERS_IN_DATABASE.add(
                EnvoyHelpers.createListenerWithRds(
                        "ads_listener_whoami_with_rds_test_01",
                        "0.0.0.0",
                        10006,
                        "ads_route_listener_whoami"
                )
        );



        ConfigurationLoader.CLUSTERS_IN_DATABASE.add(
                EnvoyHelpers.createCluster("ads_whoami_cluster_test_01", "whoami", 80)
        );

        ConfigurationLoader.CLUSTERS_IN_DATABASE.add(
                EnvoyHelpers.createCluster("ads_whoami_cluster_test_02", "whoami", 80)
        );

        ConfigurationLoader.CLUSTERS_IN_DATABASE.add(
                EnvoyHelpers.createCluster("ads_www_01_cluster_test_01", "nginx-1", 80)
        );

        ConfigurationLoader.CLUSTERS_IN_DATABASE.add(
                EnvoyHelpers.createCluster("ads_http_upstream_01_cluster_test_01", "http-upstream1", 80)
        );

        ConfigurationLoader.CLUSTERS_IN_DATABASE.add(
                EnvoyHelpers.createCluster("ads_www_tntv_pf", "nginx-tntv", 80)
        );



        ConfigurationLoader.ROUTES_IN_DATABASE.add(
                EnvoyHelpers.createRouteConfiguration("ads_route_listener_whoami", "whoami.ifenua-indirect.pf", "ads_whoami_cluster_test_01")
        );

        ConfigurationLoader.ROUTES_IN_DATABASE.add(
                EnvoyHelpers.createRouteConfiguration("ads_route_whoami_listener_default_443", "whoami.dynlist.pf", "ads_whoami_cluster_test_01")
        );

        ConfigurationLoader.ROUTES_IN_DATABASE.add(
                EnvoyHelpers.createRouteConfiguration("ads_route_http_upstream_1_listener_default_443", "domain1.com", "ads_http_upstream_01_cluster_test_01")
        );

        ConfigurationLoader.ROUTES_IN_DATABASE.add(
                EnvoyHelpers.createRouteConfiguration("ads_route_nginx_1_listener_default_443", "nginx1.com", "ads_www_01_cluster_test_01")
        );



        // TODO - Load from file
        ConfigurationLoader.SECRETS_IN_DATABASE.add(
                EnvoyHelpers.createTlsSecret(
                        ConfigurationLoader.DEFAULT_SECRET_NAME,
                        "-----BEGIN CERTIFICATE-----\n" +
                        "MIIC/DCCAeQCCQCNS1ubwqpFOzANBgkqhkiG9w0BAQsFADBAMQswCQYDVQQGEwJV\n" +
                        "UzELMAkGA1UECAwCQ0ExGDAWBgNVBAoMD015RXhhbXBsZSwgSW5jLjEKMAgGA1UE\n" +
                        "AwwBKjAeFw0yMTEwMjYwMzA3NTNaFw0yMjEwMjYwMzA3NTNaMEAxCzAJBgNVBAYT\n" +
                        "AlVTMQswCQYDVQQIDAJDQTEYMBYGA1UECgwPTXlFeGFtcGxlLCBJbmMuMQowCAYD\n" +
                        "VQQDDAEqMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA6lbao6Y3/zqQ\n" +
                        "JDKZ1DKo60Go+PVcczSFspvFLwWzAm31ZAAbYL16kjYJfiriLOIZ7XbxsLK5Nukz\n" +
                        "1dVnVdnxU7QLLuMMYvPAC9MmdtkIIpfQT7T+s5Di7ajuuxAHVXZXAREBE2gRyzds\n" +
                        "VjSYWZbaiE5um5963mZkRB0ietzgXspFi1vYtxxY5evq5pFuvm20EE60YUW9f7E3\n" +
                        "yUSj2Ys3giILFTSrArPq2oSiwpcPSZwubArdQpXYzJabAcR9jAtXG0FKKlTtwmCO\n" +
                        "4n9CyChZE5mfpD4Z8VzNuXcur1+UVhbWGcDsARXoJzTOQ93w3OEhnrrWL5lgNOAs\n" +
                        "TEDwiUudcQIDAQABMA0GCSqGSIb3DQEBCwUAA4IBAQCsbPM9PSvhiCXmaMYiHZHM\n" +
                        "RzjsjfwNlJQyPuaNjGjWC74XNwQZvqGaHWWnnuocGBThviykFpUvHKE0S8OtSg76\n" +
                        "zuwLQ9DgGNw+Mm2eWoqwioFiXHVll7xCEHxTvyx38beSjBSGSAQeQiFzzLZtqL5f\n" +
                        "KjdqckoClOS6yoEkSRzb9pA5AQAL9m/ia8wmp/4kjBgStv4K5vcVqV0xi705FFP9\n" +
                        "8nHsH+8qqaAAZ1jvjFqILq/YKznWRLIi1LQiazS1eocvMpNI6iZ3gmMFTFoMX55/\n" +
                        "RJbM4CGjae5LYsLfzc80wLFHoBAzQF4CF0Yf6e7k21AeWOroJ16AF048WtbKn9Fn\n" +
                        "-----END CERTIFICATE-----",
                        "-----BEGIN PRIVATE KEY-----\n" +
                        "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDqVtqjpjf/OpAk\n" +
                        "MpnUMqjrQaj49VxzNIWym8UvBbMCbfVkABtgvXqSNgl+KuIs4hntdvGwsrk26TPV\n" +
                        "1WdV2fFTtAsu4wxi88AL0yZ22Qgil9BPtP6zkOLtqO67EAdVdlcBEQETaBHLN2xW\n" +
                        "NJhZltqITm6bn3reZmREHSJ63OBeykWLW9i3HFjl6+rmkW6+bbQQTrRhRb1/sTfJ\n" +
                        "RKPZizeCIgsVNKsCs+rahKLClw9JnC5sCt1CldjMlpsBxH2MC1cbQUoqVO3CYI7i\n" +
                        "f0LIKFkTmZ+kPhnxXM25dy6vX5RWFtYZwOwBFegnNM5D3fDc4SGeutYvmWA04CxM\n" +
                        "QPCJS51xAgMBAAECggEAOhgUFdPkN/LVvxOITTHN7JyXfjidlbXkmzXAuXqJOUX3\n" +
                        "OrZIE0CF/W9GBTAuaAAZe2QsYKi9/93qSs2f04m3KRAOYa5J6NISvxu2gmoleSX3\n" +
                        "r3roVa3KhC6IGHnNx6MRyKLliGEZYC66PdjGGBesz3PaOdxcgnwRyU0Luje9q+Gx\n" +
                        "zotXfZ9OEHQjCKoVPeL+wc3jsIlzhiMm/DoGh/zbyZZzdLqT1Bm/FwPrkss2t0h3\n" +
                        "zDzoIqwpBSiGQSmI5V1J3iqj+u/P5g984NfgbFT6rppez77EgENOxdD+X8sPinE8\n" +
                        "cKg/8zC2x8S8/1c5eEGJQbmHBRt4lJrv0kuRU1XnwQKBgQD4B2TFQV4jC4cSA7zB\n" +
                        "RzJ3wGCj+RYfra3P4jQ8IxBPS2e0sFOoBaWB8pc0W7bOjW9ilVsZKAASlFL++Zly\n" +
                        "GWkf/v6uUlIiPsysumOHV7heGsRqpd3dWUZrH0g6OfGXYml1xnHhmjOht6c6TgCc\n" +
                        "jBngOjakQNu4lmaaY8z5T9vlOQKBgQDx3tT+XLl49wVSc9qmoLcW3JCGMqBnAlQg\n" +
                        "NBEyYEDEqNWkq7iGFvub5glXy6DG0TUVQQSQIQJX2r06uR6svEsn0bT8F7wIsw/8\n" +
                        "DjlNrKM8oxFoM4e0F8R3GZCQpJ1AyuLzeryurMU4W3yMcXUBwiHhVdmp6Qd23c8T\n" +
                        "PF4JzZfx+QKBgG1czTkQvpJTReZSkYrjesREphgG/5NOQGJ2SjPt/gYIqJyKVwiy\n" +
                        "HK00qykh+3QN9vwQARARjB6lGgdlRRyDSdAa3m88ywxghlzu/m2x5xBPWyXvJumv\n" +
                        "sJYRQAa0f+sRMJEGxDYGiReYY/sYY7qjJ9GvuWIpWviEEI+oy7tuU/Y5AoGAUWN/\n" +
                        "XryK+OZ+lvk/OVTZZ0r/IvlEOoVxE4kRxFbZVJPPmGLka9KuBg7JVA7EYkKhzy4a\n" +
                        "v/krla6YgHsslEmkLJkHgtDlyDOhDFso8zdFkrD4uYylfHeG9+DVYabS96uN5QqH\n" +
                        "FWwzzTwT1BqVmerehbnizacJiPkopjtpFXlmSNkCgYEAiRDmOe0TUSsIsTX/LQRc\n" +
                        "DPGl4lf36Ic3OhlLzPzvsJNj8sR2NgFFYmuarUjzAvM1N5r4SH9FrUOelyQ+cb2G\n" +
                        "l3X3D2FBwYApFU28M8UEhNUaRhs8TKZWHNO18PBuMJ3T+qCiPsDE1C6yvaERMng+\n" +
                        "GIuVUDyhuedgJ5yhnqT/zW0=\n" +
                        "-----END PRIVATE KEY-----"
                )
        );

        ConfigurationLoader.SECRETS_IN_DATABASE.add(
                EnvoyHelpers.createTlsSecret(
                        "ads_secret_domain_1_cert",
                        "-----BEGIN CERTIFICATE-----\n" +
                        "MIIDIDCCAggCCQDDoEOeJU1YkDANBgkqhkiG9w0BAQsFADBSMQswCQYDVQQGEwJV\n" +
                        "UzELMAkGA1UECAwCQ0ExGDAWBgNVBAoMD015RXhhbXBsZSwgSW5jLjEcMBoGA1UE\n" +
                        "AwwTZG9tYWluMS5leGFtcGxlLmNvbTAeFw0yMTEwMjEyMjI1MjlaFw0yMjEwMjEy\n" +
                        "MjI1MjlaMFIxCzAJBgNVBAYTAlVTMQswCQYDVQQIDAJDQTEYMBYGA1UECgwPTXlF\n" +
                        "eGFtcGxlLCBJbmMuMRwwGgYDVQQDDBNkb21haW4xLmV4YW1wbGUuY29tMIIBIjAN\n" +
                        "BgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEArBgGll/XH3fOrBT8HDrqr3Jj2vAm\n" +
                        "BZrXAezG0EATNjy2ykDg4G3sxe4n9VLn5oOaoYVJA572VjX4kuzAorDLKP6Dw9gg\n" +
                        "v/tvgMdc4USxTTtjK1KIBjrGE7Xyw3UKwO6k34XStDuN4A9MJ14sZfmmwyp1Sg6T\n" +
                        "OR737YVs5WqxYZ+P+MAWFPFTnCK+Bo9d/5J3llBoKWT3Ou76vabkzp1emwQY8RDX\n" +
                        "tt1U8zFm0L4zcnSDcktWbSo95/GWUg+ULNpMrLSsibvaBULGjE7Wzs5H+SejCNrh\n" +
                        "dGL6eqK3YECflfPEWQpZAnwEVzjZfjqYjRTIwDFj5VS5/3kdqzli/cG0lQIDAQAB\n" +
                        "MA0GCSqGSIb3DQEBCwUAA4IBAQAIltOq61MSvMN9bWVN3VsQQFJ8vjoCi/KknNb6\n" +
                        "UANzv1n/36xfLac12fzzy7twDAgvntplLk4LYf+IQLtg015oCsLvousOpqIuKSPH\n" +
                        "1zjYDROmAEeebF4Sc8EnXakojobR0RmEKXmf96CwYBT6RFl6xPVge8A1Ih9NiULX\n" +
                        "bE3y+9anvywfiv7UtyN6Mb2Tr2Y3v779GAJ8HurOicoGlge2DruYFe4kz0auKxFQ\n" +
                        "r3u5h7V0N20bpTqb1orDDLIoKQu5Yw6JiPk2QnikIuB6yBiHZhaSW8bRTeJmgORa\n" +
                        "cEFvCq3/GAhIiJJkQ0MBOdVE4UKG+hNXhRMSIJMGRYhK6CiP\n" +
                        "-----END CERTIFICATE-----\n",
                        "-----BEGIN PRIVATE KEY-----\n" +
                        "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCsGAaWX9cfd86s\n" +
                        "FPwcOuqvcmPa8CYFmtcB7MbQQBM2PLbKQODgbezF7if1Uufmg5qhhUkDnvZWNfiS\n" +
                        "7MCisMso/oPD2CC/+2+Ax1zhRLFNO2MrUogGOsYTtfLDdQrA7qTfhdK0O43gD0wn\n" +
                        "Xixl+abDKnVKDpM5HvfthWzlarFhn4/4wBYU8VOcIr4Gj13/kneWUGgpZPc67vq9\n" +
                        "puTOnV6bBBjxENe23VTzMWbQvjNydINyS1ZtKj3n8ZZSD5Qs2kystKyJu9oFQsaM\n" +
                        "TtbOzkf5J6MI2uF0Yvp6ordgQJ+V88RZClkCfARXONl+OpiNFMjAMWPlVLn/eR2r\n" +
                        "OWL9wbSVAgMBAAECggEAbQcLpVkywQz2Mq9YycnJxT2DZcGfVQ20CdQoYOc3RLVS\n" +
                        "WPChh44c44juZ84OfUwDOzFmAJVFG3k18l9r66Z2D5Kvh/P1S6vQCJZFkbIjYfqt\n" +
                        "6VRwOQIjW3l6Q7pzBagrbDEsPGM+jyUh6g8Psctoi1zq0fBJ9XZZPHG7e90MY/vZ\n" +
                        "BcHZiC7fecYWjJu3oQ2PADpS0vnxRLUI3/qihYYdYUhqGGKsKn8GT/RNZJv9lp1R\n" +
                        "ojYvdUEpviz+fIFAuNZWFPPAlNcPQo0PV8ncixAobaYNPRtVoSG0WDXgIGxUQGjT\n" +
                        "fqJTNayRv5d6gONNdzg0gqjQAw109s6s/FHVp8EeAQKBgQDWf6TAtIefC/HINOyw\n" +
                        "KuvQpUvbj/NoqRwt3OGm1pTDTTLKwj/EoWjK1t5C6VTY3fc1izLKLfwXO4nn5INj\n" +
                        "4lxxMfl+gg1tSc2Tk43h7x52r+iojJERLeW57v5T5Fmaby0+zwyjBTrKAoxIOeHM\n" +
                        "Pkhd/AU3dRIeQI3PBjBAD9zyGQKBgQDNZAbqxXqHCB3rJd6iAy6xHb95ekorPHOE\n" +
                        "oma3/99aP0e3JJOvdqgHzHNRIUMQR5KpKHQV/ffM56cie1xC37Ub1D7xmjrg+5qT\n" +
                        "hXjqFyYtcJ4C2fREHRvyv0W7DCcEFh8WHaLXezva3Cn9VlCew8OQlNYiGwqGaSs1\n" +
                        "t32hoxL93QKBgEnwYBtpX6KsnGC2Fay4budSQ27hFTfLq4IHtOUs7MTR1KNeCFtA\n" +
                        "hH3/SUhpZC0JfpC/dfeJmG9tv6Dm/X+t5M3EO0univdUTIAFihAKvEaPZrTLF6qD\n" +
                        "VsAcKSFEArsgfck72BBN2jEOZcrz4Ojlw05adHXkbiVtfTSS3okGrtPpAoGBAKsL\n" +
                        "rD8D6oBdoBzeUsP1hoL11SmjT/UlyMYiQQzmx+juXhkFGwC6/kBQYZCk9KawFFFP\n" +
                        "YS2XbTB1ktjChxkiGD9uyGSIHpSStC46r3GziZW5/b0+KZA39bh41edpvWxHx+ex\n" +
                        "EpYFCNnBFC7oHQe63Dih2ppYjrFVECkSAEwF9KD5AoGAAv3P+NBOy4AnS3wacVlQ\n" +
                        "/gs1hp2BUh8YEPFyl3rDOmUV9XO8rX2pKf711ALAuxkP1IV2dr/siLqSvjVctZgh\n" +
                        "lhtiCsMsswCKNpFFCJZNnzWVy+muX3varmzg1+41FmpMoWcH2pD/ggn5EVA1UDfx\n" +
                        "JKjdizvKrBSuvI4ta0wDc6w=\n" +
                        "-----END PRIVATE KEY-----"
                )
        );


    }

    private static Set<EnvoyHelpers.HttpConfig> createDataForDynamicListener443() {

        EnvoyHelpers.HttpConfig httpConfig1 = new EnvoyHelpers.HttpConfig();
        httpConfig1.setRdsConfigName("ads_route_whoami_listener_default_443");
        httpConfig1.setSdsConfigNames(Collections.singleton("ads_secret_default_cert"));
        httpConfig1.setDomainNames(Collections.singleton("whoami.dynlist.pf"));


        EnvoyHelpers.HttpConfig httpConfig2 = new EnvoyHelpers.HttpConfig();
        httpConfig2.setRdsConfigName("ads_route_http_upstream_1_listener_default_443");
        httpConfig2.setSdsConfigNames(Collections.singleton("ads_secret_domain_1_cert"));
        httpConfig2.setDomainNames(SetUtils.hashSet("domain1.com", "http_upstream.dynlist.dev"));


        EnvoyHelpers.HttpConfig httpConfig3 = new EnvoyHelpers.HttpConfig();
        httpConfig3.setRdsConfigName("ads_route_nginx_1_listener_default_443");
        // Without sds config names, the default cert will be used
        httpConfig3.setDomainNames(SetUtils.hashSet("nginx1.com", "nginx1.dynlist.dev"));


        return SetUtils.hashSet(
                httpConfig1,
                httpConfig2,
                httpConfig3
        );
    }




    private String defaultConfigName = "default";
    private GroupConfigDto defaultConfig;
	private final AtomicLong lastConfigModified = new AtomicLong(0);
	private Map<String, GroupConfigDto> groupConfigMap = new ConcurrentHashMap<>();
	private SimpleCacheWrapper<String> cache = ConfigurationLoader.makeCacheForGroupingWithNodeCluster();
	private boolean isReady = false;
	// Thread lock for waiting config loading
	private Object lockObj = new Object();







	@PostConstruct
	private void init() {

        log.info("Init Configuration Loader");

        this.loadConfigAllInPath();
        this.isReady = true;
	}


	public SimpleCacheWrapper<String> getCache() {
		if(!isReady) {
			// Waiting for cache to be load
			synchronized(lockObj) {
				try {
                    log.debug("THREAD WAIT");
                    this.lockObj.wait(TimeUnit.SECONDS.toMillis(30));

				} catch (InterruptedException e) {
					log.error(e.getMessage(), e);
					Thread.currentThread().interrupt();
				}
			}
		}
		
		return this.cache;
	}
	

	public void reloadConfig() {
		String groupName = "default"; // TODO - Need to change ? Maybe put the name of the node
		
		if(groupName != null) {
            GroupConfigDto changedConfig = loadConfig();
			
			if(groupName.equals(this.defaultConfigName)) {
				this.defaultConfig = changedConfig;
				
                this.resetCache();
				
				log.info("reload default config");
			} else {
				this.groupConfigMap.put(groupName, changedConfig);
				
                this.updateSnapshot(groupName, changedConfig);
				
				log.info("reload config");
			}
			
			this.lastConfigModified.set(System.currentTimeMillis());
		}
	}
	

    // TODO - Entry for ControlPanelService
	public void loadConfigAllInPath() {
		synchronized(lockObj) {

            log.debug("LOAD CONFIG ALL IN PATH");

            this.loadDefaultCerts();

            this.reloadConfig();

			this.resetCache();

            this.lastConfigModified.set(System.currentTimeMillis());

            this.lockObj.notifyAll();

            log.debug("THREAD NOTIFY ALL");
		}
	}

    // TODO - Use this to fill SECRET
    @SneakyThrows
    private void loadDefaultCerts() {

        File defaultCert = new ClassPathResource("/certs/default.crt.pem").getFile();

        File defaultPrivateKey = new ClassPathResource("/certs/default.key.pem").getFile();

        if (!defaultCert.exists() || !defaultPrivateKey.exists()) {
            throw new RuntimeException("Error when load default cert and/or private key");
        }

    }


	private void resetCache() {

        log.info("Reset cache");

		if(this.defaultConfig != null) {
            this.updateDefaultSnapshot();
		}

        this.groupConfigMap.keySet().forEach(group -> {
            this.updateSnapshot(group, this.groupConfigMap.get(group));
		});
		
		log.info("all snapshot updated by reset cache");
	}
	

	private void updateDefaultSnapshot() {

        log.info("update default snapshot");

        String snapshotVersion = this.cache.makeNewVersion();

        if (this.cache != null && this.cache.getSnapshot(this.defaultConfigName) != null) {
            String listenersVersion = this.cache
                    .getSnapshot(this.defaultConfigName)
                    .listeners()
                    .version();

            String clustersVersion = this.cache
                    .getSnapshot(this.defaultConfigName)
                    .clusters()
                    .version();

            log.info(
                    "Update default snapshot listeners version from <{}> / clusters version from <{}> / to <{}>",
                    listenersVersion,
                    clustersVersion,
                    snapshotVersion
            );
        }

        Snapshot snapshot = Snapshot.create(
                ConfigurationLoader.CLUSTERS_IN_DATABASE,
                ConfigurationLoader.ENDPOINTS_IN_DATABASE,
                ConfigurationLoader.LISTENERS_IN_DATABASE,
                ConfigurationLoader.ROUTES_IN_DATABASE,
                ConfigurationLoader.SECRETS_IN_DATABASE,
                snapshotVersion
        );

        this.addSnapshot(
                snapshotVersion,
                SnapshotState.builder()
                        .version(snapshotVersion)
                        .snapshot(snapshot)
                        .build()
        );

        this.cache.setSnapshot(
                this.defaultConfigName,
                snapshot
		);
		
		log.info("[{}] (default) group snapshot updated", defaultConfigName);
	}
	

	private void updateSnapshot(String groupName, GroupConfigDto group) {

        log.info("Update snapshot / {} / {}", groupName, group);

		List<Listener> listenerList = new ArrayList<>();
		List<Cluster> clusterList = new ArrayList<>();
		List<RouteConfiguration> routeConfigurationList = new ArrayList<>();
		List<ClusterLoadAssignment> endpointList = new ArrayList<>(); // TODO - Not implemented ATM
		List<Secret> secretList = new ArrayList<>();

		clusterList.addAll(group.getClusterList());
		listenerList.addAll(group.getListenerList());
        routeConfigurationList.addAll(group.getRouteList());
		secretList.addAll(group.getSecretList());


        String snapshotVersion = this.cache.makeNewVersion();
        Snapshot snapshot = Snapshot.create(
                clusterList,
                ImmutableList.of(), // TODO - Not implemented ATM
                listenerList,
                routeConfigurationList,
                secretList,
                snapshotVersion
        );

        this.addSnapshot(
                snapshotVersion,
                SnapshotState.builder()
                        .version(snapshotVersion)
                        .snapshot(snapshot)
                        .build()
        );

        cache.setSnapshot(
                groupName,
                snapshot
        );
		
		log.info("[{}] group snapshot updated.", groupName);
	}
	

	private GroupConfigDto loadConfig() {

        log.debug("loadConfig()");

        // Work as if config came from Database

        return GroupConfigDto.builder()
                .name("default")
                .listenerList(ConfigurationLoader.LISTENERS_IN_DATABASE)
                .clusterList(ConfigurationLoader.CLUSTERS_IN_DATABASE)
                .routeList(ConfigurationLoader.ROUTES_IN_DATABASE)
                .endpointList(ConfigurationLoader.ENDPOINTS_IN_DATABASE)
                .secretList(ConfigurationLoader.SECRETS_IN_DATABASE)
                .build();

	}
	

	private static SimpleCacheWrapper<String> makeCacheForGroupingWithNodeCluster() {

        log.debug("makeCacheForGroupingWithNodeCluster()");

        return new SimpleCacheWrapper<>(new NodeGroup<String>() {
			@Override
			public String hash(io.envoyproxy.envoy.api.v2.core.Node node) {

                log.debug("Simple cache wrapper - hash - node : [id: {}, cluster: {}, meta: {}] - API V2 -> RETURN NULL", node.getId(), node.getCluster(), node.getMetadata());
                // Not support V2
				return null;
			}

			@Override
			public String hash(Node node) {
				log.debug("Simple cache wrapper - hash - node : [id: {}, cluster: {}, meta: {}]", node.getId(), node.getCluster(), node.getMetadata());

				// return node.getCluster(); // TODO - Maybe change to 'default' the default key for the default group of snapshot ?
                return "default"; // TODO - TEST DEV
			}
	    });
	}
	
	public long lastConfigModified() {
		return this.lastConfigModified.get();
	}



    public Snapshot getDefaultGroupCacheSnapshot() {

        log.info("Get default group snaptshot");

        return this.cache.getSnapshot(this.defaultConfigName);
    }


    // Clusters
    public void addCluster(Cluster cluster) {

        log.info("Add new cluster to database");

        ConfigurationLoader.CLUSTERS_IN_DATABASE.add(cluster);

        this.reloadConfig();

    }
    // TODO - Implement other CRUD clusters


    // # Listeners
    public void addListener(Listener listener) {

        log.info("Add new listener to database");

        ConfigurationLoader.LISTENERS_IN_DATABASE.add(listener);

        this.reloadConfig();

    }

    public void updateListenerDbData(String name, Listener updatedListener) {

        log.info("Update listener DB Data for name <{}>", name);

        this.removeListenerDbData(name);
        ConfigurationLoader.LISTENERS_IN_DATABASE.add(updatedListener);

        this.reloadConfig();

    }

    public void removeListenerDbDataAndReloadConfig(String name) {

        log.info("Remove listener DB Data for name <{}>", name);

        this.removeListenerDbData(name);

        this.reloadConfig();

    }

    private void removeListenerDbData(String name) {

        log.info("Private remove listener DB Data for name <{}>", name);

        List<Listener> collect = ConfigurationLoader.LISTENERS_IN_DATABASE
                .stream()
                .filter(s -> !s.getName().equalsIgnoreCase(name))
                .collect(Collectors.toList());

        ConfigurationLoader.LISTENERS_IN_DATABASE.clear();
        ConfigurationLoader.LISTENERS_IN_DATABASE.addAll(collect);

    }


    // # Routes
    public void addRoute(RouteConfiguration routeConfiguration) {

        log.info("Add new route to database");

        ConfigurationLoader.ROUTES_IN_DATABASE.add(routeConfiguration);

        this.reloadConfig();
    }

    public void updateRouteConfigurationDbDataAndReloadConfig(String name, RouteConfiguration updatedRouteConfiguration) {

        log.info("Update route configuration DB Data for name <{}>", name);

        this.removeRouteConfigurationDbData(name);
        ConfigurationLoader.ROUTES_IN_DATABASE.add(updatedRouteConfiguration);

        this.reloadConfig();

    }

    public void removeRouteConfigurationDbDataAndReloadConfig(String name) {

        log.info("Remove route DB Data for name <{}>", name);

        this.removeRouteConfigurationDbData(name);

        this.reloadConfig();

    }

    private void removeRouteConfigurationDbData(String name) {

        log.info("Private remove route DB Data for name <{}>", name);

        List<RouteConfiguration> collect = ConfigurationLoader.ROUTES_IN_DATABASE
                .stream()
                .filter(s -> !s.getName().equalsIgnoreCase(name))
                .collect(Collectors.toList());

        ConfigurationLoader.ROUTES_IN_DATABASE.clear();
        ConfigurationLoader.ROUTES_IN_DATABASE.addAll(collect);

    }


    // # Secrets
    public void addSecret(Secret secret) {

        log.info("Add new secret to database");

        ConfigurationLoader.SECRETS_IN_DATABASE.add(secret);

        this.reloadConfig();

    }

    public void updateSecretDbDataAndReloadConfig(String name, Secret updatedSecret) {

        log.info("Update secret configuration DB Data for name <{}>", name);

        this.removeSecretDbData(name);
        ConfigurationLoader.SECRETS_IN_DATABASE.add(updatedSecret);

        this.reloadConfig();

    }

    public void removeSecretDbDataAndReloadConfig(String name) {

        log.info("Remove secret DB Data for name <{}>", name);

        this.removeSecretDbData(name);

        this.reloadConfig();

    }

    private void removeSecretDbData(String name) {

        log.info("Private remove secret DB Data for name <{}>", name);

        List<Secret> collect = ConfigurationLoader.SECRETS_IN_DATABASE
                .stream()
                .filter(s -> !s.getName().equalsIgnoreCase(name))
                .collect(Collectors.toList());

        ConfigurationLoader.SECRETS_IN_DATABASE.clear();
        ConfigurationLoader.SECRETS_IN_DATABASE.addAll(collect);

    }








    public void resetToTheLastGoodSnapshot(String typeUrl, String lastGoodVersion) {

        log.warn("SOFT RESET DB DATA !!!");
        log.warn("Reset to the last good snapshot for type url <{}> and last good version <{}>", typeUrl, lastGoodVersion);

        // Search for valid configuration
        if (this.snapshotByVersion.containsKey(lastGoodVersion)) {

            this.loadTheLastGoodVersionByTypeUrl(
                    typeUrl,
                    this.snapshotByVersion.get(lastGoodVersion).getSnapshot()
            );


            // Manage infinite loop
            if (this.tryToRollbackCountByVersionByTypeUrl.containsKey(typeUrl)) {

                int counter = this.tryToRollbackCountByVersionByTypeUrl
                        .get(typeUrl)
                        .getOrDefault(lastGoodVersion, 0);

                counter = counter + 1;

                log.debug("Increment reset count by version by type url for type url <{}> / version <{}> / counter <{}>", typeUrl, lastGoodVersion, counter);

                this.tryToRollbackCountByVersionByTypeUrl
                        .get(typeUrl)
                        .put(
                                lastGoodVersion,
                                counter
                        );

            } else {

                log.debug("Add reset count by version by type url for type url <{}> / version <{}>", typeUrl, lastGoodVersion);

                HashMap<String, Integer> countByVersion = new HashMap<>();
                countByVersion.put(
                        lastGoodVersion,
                        0
                );
                this.tryToRollbackCountByVersionByTypeUrl.put(typeUrl, countByVersion);

            }

            // Stop infinite loop
            if (this.tryToRollbackCountByVersionByTypeUrl.get(typeUrl).getOrDefault(lastGoodVersion, 0) > 3) {
                log.error("Stop infinite loop for type url <{}> / version <{}>", typeUrl, lastGoodVersion);
                this.hardResetDbData();

                log.debug("Reset counter for type url <{}> / version <{}>", typeUrl, lastGoodVersion);
                this.tryToRollbackCountByVersionByTypeUrl.get(typeUrl).put(lastGoodVersion, 0);

                return ;
            }

        } else {
            log.error("No last good version found for this type url <{}> - do a hard reset", typeUrl);

            this.hardResetDbData();

        }

    }

    // TODO
    public void hardResetDbData() {

        log.warn("HARD RESET DB DATA !!!");

        this.clearDbData();

        this.reloadConfig();

    }



    private void clearDbData() {

        log.warn("CLEAR DB DATA !!!");

        ConfigurationLoader.CLUSTERS_IN_DATABASE.clear();
        ConfigurationLoader.LISTENERS_IN_DATABASE.clear();
        ConfigurationLoader.ROUTES_IN_DATABASE.clear();
        ConfigurationLoader.SECRETS_IN_DATABASE.clear();
        ConfigurationLoader.ENDPOINTS_IN_DATABASE.clear();

    }

    private void loadTheLastGoodVersionByTypeUrl(String typeUrl, Snapshot snapshot) {

        log.debug("Load the last good version by type url <{}>", typeUrl);

        switch (typeUrl) {

            case Resources.V3.CLUSTER_TYPE_URL:
                log.debug("Load the last good version by type url for type url <{}> and snapshot version <{}>", typeUrl, snapshot.clusters().version());
                ConfigurationLoader.CLUSTERS_IN_DATABASE.clear();
                ConfigurationLoader.CLUSTERS_IN_DATABASE.addAll(
                        snapshot.clusters().resources().values()

                );

                this.reloadConfig();
                break;

            case Resources.V3.LISTENER_TYPE_URL:
                log.debug("Load the last good version by type url for type url <{}> and snapshot version <{}>", typeUrl, snapshot.listeners().version());
                ConfigurationLoader.LISTENERS_IN_DATABASE.clear();
                ConfigurationLoader.LISTENERS_IN_DATABASE.addAll(
                        snapshot.listeners().resources().values()
                );

                this.reloadConfig();
                break;

            case Resources.V3.ROUTE_TYPE_URL:
                log.debug("Load the last good version by type url for type url <{}> and snapshot version <{}>", typeUrl, snapshot.routes().version());
                ConfigurationLoader.ROUTES_IN_DATABASE.clear();
                ConfigurationLoader.ROUTES_IN_DATABASE.addAll(
                        snapshot.routes().resources().values()
                );

                this.reloadConfig();
                break;

            case Resources.V3.SECRET_TYPE_URL:
                log.debug("Load the last good version by type url for type url <{}> and snapshot version <{}>", typeUrl, snapshot.secrets().version());
                ConfigurationLoader.SECRETS_IN_DATABASE.clear();
                ConfigurationLoader.SECRETS_IN_DATABASE.addAll(
                        snapshot.secrets().resources().values()
                );

                this.reloadConfig();
                break;

            case Resources.V3.ENDPOINT_TYPE_URL:
                log.debug("Load the last good version by type url for type url <{}> and snapshot version <{}>", typeUrl, snapshot.endpoints().version());
                ConfigurationLoader.ENDPOINTS_IN_DATABASE.clear();
                ConfigurationLoader.ENDPOINTS_IN_DATABASE.addAll(
                        snapshot.endpoints().resources().values()
                );

                this.reloadConfig();
                break;

            default:
                log.error("TypeUrl not supported for rollback <{}> - DO HARD RESET", typeUrl);
                this.hardResetDbData();
                break;
        }

    }

    private SnapshotState addSnapshot(String version, SnapshotState snapshotState) {

        log.warn("Save snapshotState to map with version {} / count {}", version, this.snapshotByVersion.size());

        if (this.snapshotByVersion.size() > 10) {

            String firstMapKey = this.snapshotByVersion.firstKey();
            log.warn("Remove from snaptshot map the version {}", firstMapKey);
            this.snapshotByVersion.remove(firstMapKey);
        }

        return this.snapshotByVersion.put(version, snapshotState);
    }






    @Data
    @Builder
    public static class SnapshotState {

        Snapshot snapshot;

        String version;

        @Builder.Default
        Boolean hasError = Boolean.FALSE; // Not used ATM

        @Builder.Default
        Boolean wasSent = Boolean.FALSE; // Not used ATM

    }

}
