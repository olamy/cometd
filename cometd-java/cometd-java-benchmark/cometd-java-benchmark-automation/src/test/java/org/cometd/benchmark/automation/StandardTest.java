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
import org.terracotta.angela.client.filesystem.RemoteFile;
import org.terracotta.angela.common.AngelaProperties;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.ToolExecutionResult;
import org.terracotta.angela.common.cluster.Barrier;
import org.terracotta.angela.common.topology.ClientArrayTopology;

import static org.terracotta.angela.client.config.custom.CustomMultiConfigurationContext.customMultiConfigurationContext;
import static org.terracotta.angela.common.clientconfig.ClientArrayConfig.newClientArrayConfig;

public class StandardTest
{
    @Test
    void name() throws Exception
    {
        System.setProperty(AngelaProperties.ROOT_DIR.getPropertyName(), "/var/tmp/angela");

        TerracottaCommandLineEnvironment env = TerracottaCommandLineEnvironment.DEFAULT.withJavaVendors("zulu").withJavaVersion("8");

        ConfigurationContext configContext = customMultiConfigurationContext()
            .clientArray(clientArray -> clientArray.terracottaCommandLineEnvironment(env).clientArrayTopology(new ClientArrayTopology(newClientArrayConfig().host("localhost"))))
            .clientArray(clientArray -> clientArray.terracottaCommandLineEnvironment(env).clientArrayTopology(new ClientArrayTopology(newClientArrayConfig().hostSerie(1, "localhost"))));

        try (ClusterFactory factory = new ClusterFactory("StandardTest::name", configContext))
        {
            ClientArray servers = factory.clientArray();
            ClientArray clients = factory.clientArray();
            int clientCount = clients.getClients().size();

            System.err.println("Starting server(s)...");
            servers.executeOnAll(cluster ->
            {
                CometDLoadServer.main(new String[]{"--auto", "--port=7070"});
            }).get();

            System.err.println("Starting client(s)...");
            jcmd(clients, "JFR.start", "name=cometd-client", "settings=profile");
            clients.executeOnAll(cluster ->
            {
                Barrier barrier = cluster.barrier("clients", clientCount);
                int clientID = barrier.await();

                CometDLoadClient.main(new String[]{"--auto", "--port=7070", "--iterations=1", "--batches=6000", "--channel=/chan" + clientID});
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
