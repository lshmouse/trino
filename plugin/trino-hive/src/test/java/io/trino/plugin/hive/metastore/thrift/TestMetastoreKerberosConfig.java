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
package io.trino.plugin.hive.metastore.thrift;

import com.google.common.collect.ImmutableMap;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.airlift.configuration.testing.ConfigAssertions.assertFullMapping;
import static io.airlift.configuration.testing.ConfigAssertions.assertRecordedDefaults;
import static io.airlift.configuration.testing.ConfigAssertions.recordDefaults;

public class TestMetastoreKerberosConfig
{
    @Test
    public void testDefaults()
    {
        assertRecordedDefaults(recordDefaults(MetastoreKerberosConfig.class)
                .setHiveMetastoreServicePrincipal(null)
                .setHiveMetastoreClientPrincipal(null)
                .setHiveMetastoreClientKeytab(null));
    }

    @Test
    public void testExplicitPropertyMappings()
            throws IOException
    {
        Path clientKeytabFile = Files.createTempFile(null, null);

        Map<String, String> properties = ImmutableMap.<String, String>builder()
                .put("hive.metastore.service.principal", "hive/_HOST@EXAMPLE.COM")
                .put("hive.metastore.client.principal", "metastore@EXAMPLE.COM")
                .put("hive.metastore.client.keytab", clientKeytabFile.toString())
                .buildOrThrow();

        MetastoreKerberosConfig expected = new MetastoreKerberosConfig()
                .setHiveMetastoreServicePrincipal("hive/_HOST@EXAMPLE.COM")
                .setHiveMetastoreClientPrincipal("metastore@EXAMPLE.COM")
                .setHiveMetastoreClientKeytab(clientKeytabFile.toString());

        assertFullMapping(properties, expected);
    }
}
