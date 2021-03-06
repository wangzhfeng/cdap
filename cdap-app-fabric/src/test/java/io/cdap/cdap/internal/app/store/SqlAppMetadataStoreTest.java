/*
 * Copyright © 2019 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.cdap.internal.app.store;

import com.opentable.db.postgres.embedded.EmbeddedPostgres;
import io.cdap.cdap.spi.data.StructuredTableAdmin;
import io.cdap.cdap.spi.data.TableAlreadyExistsException;
import io.cdap.cdap.spi.data.sql.PostgresInstantiator;
import io.cdap.cdap.spi.data.sql.PostgresSqlStructuredTableAdmin;
import io.cdap.cdap.spi.data.sql.SqlStructuredTableRegistry;
import io.cdap.cdap.spi.data.sql.SqlTransactionRunner;
import io.cdap.cdap.store.StoreDefinition;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import javax.sql.DataSource;

/**
 * Tests {@link AppMetadataStore} using SQL.
 */
public class SqlAppMetadataStoreTest extends AppMetadataStoreTest {

  @ClassRule
  public static final TemporaryFolder TEMP_FOLDER = new TemporaryFolder();

  private static EmbeddedPostgres pg;

  @BeforeClass
  public static void beforeClass() throws IOException, TableAlreadyExistsException {
    pg = PostgresInstantiator.createAndStart(TEMP_FOLDER.newFolder());
    DataSource dataSource = pg.getPostgresDatabase();
    SqlStructuredTableRegistry structuredTableRegistry = new SqlStructuredTableRegistry(dataSource);
    structuredTableRegistry.initialize();
    StructuredTableAdmin structuredTableAdmin =
      new PostgresSqlStructuredTableAdmin(structuredTableRegistry, dataSource);
    transactionRunner = new SqlTransactionRunner(structuredTableAdmin, dataSource);
    StoreDefinition.AppMetadataStore.createTables(structuredTableAdmin, false);
  }

  @AfterClass
  public static void afterClass() throws IOException {
    pg.close();
  }
}
