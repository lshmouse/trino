/*
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
package io.trino.plugin.deltalake;

import com.google.common.collect.ImmutableSet;
import io.trino.Session;
import io.trino.hdfs.DynamicHdfsConfiguration;
import io.trino.hdfs.HdfsConfig;
import io.trino.hdfs.HdfsConfiguration;
import io.trino.hdfs.HdfsConfigurationInitializer;
import io.trino.hdfs.HdfsContext;
import io.trino.hdfs.HdfsEnvironment;
import io.trino.hdfs.authentication.NoHdfsAuthentication;
import io.trino.plugin.hive.NodeVersion;
import io.trino.plugin.hive.metastore.file.FileHiveMetastore;
import io.trino.plugin.hive.metastore.file.FileHiveMetastoreConfig;
import io.trino.spi.security.ConnectorIdentity;
import io.trino.testing.DistributedQueryRunner;
import io.trino.testing.QueryRunner;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import static io.trino.plugin.deltalake.DeltaLakeConnectorFactory.CONNECTOR_NAME;
import static io.trino.testing.TestingSession.testSessionBuilder;

public class TestDeltaLakeTableWithCustomLocationUsingHiveMetastore
        extends BaseDeltaLakeTableWithCustomLocation
{
    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        Session session = testSessionBuilder()
                .setCatalog(CATALOG_NAME)
                .setSchema(SCHEMA)
                .build();

        DistributedQueryRunner.Builder<?> builder = DistributedQueryRunner.builder(session);
        DistributedQueryRunner queryRunner = builder.build();

        Map<String, String> connectorProperties = new HashMap<>();
        HdfsConfig hdfsConfig = new HdfsConfig();
        HdfsConfiguration hdfsConfiguration = new DynamicHdfsConfiguration(new HdfsConfigurationInitializer(hdfsConfig), ImmutableSet.of());
        hdfsEnvironment = new HdfsEnvironment(hdfsConfiguration, hdfsConfig, new NoHdfsAuthentication());
        metastoreDir = Files.createTempDirectory("test_delta_lake").toFile();
        FileHiveMetastoreConfig config = new FileHiveMetastoreConfig()
                .setCatalogDirectory(metastoreDir.toURI().toString())
                .setMetastoreUser("test");
        hdfsContext = new HdfsContext(ConnectorIdentity.ofUser(config.getMetastoreUser()));
        metastore = new FileHiveMetastore(
                new NodeVersion("testversion"),
                hdfsEnvironment,
                false,
                config);
        connectorProperties.putIfAbsent("delta.unique-table-location", "true");
        connectorProperties.putIfAbsent("hive.metastore", "file");
        connectorProperties.putIfAbsent("hive.metastore.catalog.dir", metastoreDir.getPath());

        queryRunner.installPlugin(new TestingDeltaLakePlugin());
        queryRunner.createCatalog(CATALOG_NAME, CONNECTOR_NAME, connectorProperties);

        queryRunner.execute("CREATE SCHEMA " + SCHEMA);

        return queryRunner;
    }
}
