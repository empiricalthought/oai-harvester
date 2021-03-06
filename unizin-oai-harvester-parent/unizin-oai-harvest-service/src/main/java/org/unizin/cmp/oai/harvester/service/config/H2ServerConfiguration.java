package org.unizin.cmp.oai.harvester.service.config;

import java.util.ArrayList;
import java.util.List;

import javax.validation.constraints.Min;

import org.h2.tools.Server;
import org.unizin.cmp.oai.harvester.service.db.ManagedH2Server;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class H2ServerConfiguration {

    @JsonProperty
    @Min(1)
    private Integer jdbcPort;

    @JsonProperty
    @Min(1)
    private Integer webPort;


    public List<ManagedH2Server> build() throws Exception {
        final List<ManagedH2Server> servers = new ArrayList<>();
        if (jdbcPort != null) {
            servers.add(new ManagedH2Server(jdbcPort, "tcp",
                    Server::createTcpServer));}

        if (webPort != null) {
            servers.add(new ManagedH2Server(webPort, "web",
                    Server::createWebServer));
        }
        return servers;
    }
}
