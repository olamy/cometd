/*
 * Copyright (c) 2008-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cometd.benchmark.automation;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.function.Predicate;

import org.cometd.benchmark.client.CometDLoadClient;
import org.cometd.benchmark.server.CometDLoadServer;
import org.junit.jupiter.api.Test;
import org.terracotta.angela.client.Client;
import org.terracotta.angela.client.ClientArray;
import org.terracotta.angela.client.ClusterFactory;
import org.terracotta.angela.client.config.ConfigurationContext;
import org.terracotta.angela.client.config.custom.CustomRemotingConfigurationContext;
import org.terracotta.angela.client.filesystem.RemoteFile;
import org.terracotta.angela.client.remote.agent.SshRemoteAgentLauncher;
import org.terracotta.angela.common.AngelaProperties;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.ToolExecutionResult;
import org.terracotta.angela.common.cluster.Barrier;
import org.terracotta.angela.common.topology.ClientArrayTopology;

import static org.terracotta.angela.client.config.custom.CustomMultiConfigurationContext.customMultiConfigurationContext;
import static org.terracotta.angela.common.AngelaProperties.SSH_STRICT_HOST_CHECKING;
import static org.terracotta.angela.common.clientconfig.ClientArrayConfig.newClientArrayConfig;

public class StandardTest
{
    @Test
    void name() throws Exception
    {
        System.setProperty(AngelaProperties.ROOT_DIR.getPropertyName(), "/tmp/angela");

        String propertiesPath = System.getProperty( "angela.properties" );

        System.setProperty(SSH_STRICT_HOST_CHECKING.getPropertyName(), Boolean.FALSE.toString());

        TerracottaCommandLineEnvironment env = TerracottaCommandLineEnvironment.DEFAULT
            .withJavaVersion("1.8").withJavaVendors( "openjdk" );//.withJavaVendors("zulu").withJavaVersion("8");

        ConfigurationContext configContext = customMultiConfigurationContext()
            .remoting(new CustomRemotingConfigurationContext().remoteAgentLauncherSupplier( () -> new SshRemoteAgentLauncher(env)))
            .clientArray(clientArray -> clientArray.terracottaCommandLineEnvironment(env)
                .clientArrayTopology(new ClientArrayTopology(newClientArrayConfig().host("load-master"))))
            .clientArray(clientArray -> clientArray.terracottaCommandLineEnvironment(env)
                .clientArrayTopology(new ClientArrayTopology(newClientArrayConfig().hostSerie(1, "load-1"))));

        try (ClusterFactory factory = new ClusterFactory("StandardTest::name", configContext))
        {
            ClientArray servers = factory.clientArray();
            ClientArray clients = factory.clientArray();
            int clientCount = clients.getClients().size();

            System.err.println("Starting server(s)...");
            servers.executeOnAll(cluster ->
            {
                System.out.println("--------------------------");
                System.out.println("          SERVER          ");
                System.out.println("--------------------------");
                //CometDLoadServer.main(new String[]{"--auto", "--port=7070"});
            }).get();

            System.err.println("Starting client(s)...");
            jcmd(clients, "JFR.start", "name=cometd-client", "settings=profile");
            clients.executeOnAll(cluster ->
            {
                Barrier barrier = cluster.barrier("clients", clientCount);
                int clientID = barrier.await();
                System.out.println("--------------------------");
                System.out.println("          CLIENT          ");
                System.out.println("--------------------------");
                //CometDLoadClient.main(new String[]{"--auto", "--port=7070", "--iterations=1", "--batches=6000", "--channel=/chan" + clientID});
            }).get();
            jcmd(clients, "JFR.dump", "name=cometd-client", "filename=cometd-client.jfr");
            jcmd(clients, "JFR.stop", "name=cometd-client");

            download(clients, "cometd-client.jfr", new File("./target/jfr"));

            System.err.println("Done.");
        }
    }

    private void jcmd(ClientArray clientArray, String... arguments)
    {
        for (Client client : clientArray.getClients())
        {
            ToolExecutionResult toolExecutionResult = clientArray.jcmd(client).executeCommand(arguments);
            if (toolExecutionResult.getExitStatus() != 0)
                throw new RuntimeException("JCMD failure: " + toolExecutionResult);
        }
    }

    private void download(ClientArray clientArray, String remoteFilename, File localTargetFolder)
    {
        String date = new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss").format(new Date());
        for (Client client : clientArray.getClients())
        {
            Predicate<RemoteFile> filter = rf -> rf.getName().equals(remoteFilename);
            client.browse(".").list().stream().filter(filter).forEach(rf ->
            {
                try
                {
                    localTargetFolder.mkdirs();
                    String localFilename = date + "_" + remoteFilename;
                    rf.downloadTo(new File(localTargetFolder, localFilename));
                }
                catch (IOException e)
                {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

}
