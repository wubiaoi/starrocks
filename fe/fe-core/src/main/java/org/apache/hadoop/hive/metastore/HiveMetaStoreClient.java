// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/external/hive/HiveMetaStoreThriftClient.java

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

package org.apache.hadoop.hive.metastore;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.common.ObjectPair;
import org.apache.hadoop.hive.common.ValidTxnList;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.conf.HiveConf.ConfVars;
import org.apache.hadoop.hive.metastore.api.AccessEntry;
import org.apache.hadoop.hive.metastore.api.AggrStats;
import org.apache.hadoop.hive.metastore.api.AlreadyExistsException;
import org.apache.hadoop.hive.metastore.api.ColumnStatistics;
import org.apache.hadoop.hive.metastore.api.ColumnStatisticsObj;
import org.apache.hadoop.hive.metastore.api.CompactionType;
import org.apache.hadoop.hive.metastore.api.ConfigValSecurityException;
import org.apache.hadoop.hive.metastore.api.CurrentNotificationEventId;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.EnvironmentContext;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.FireEventRequest;
import org.apache.hadoop.hive.metastore.api.FireEventResponse;
import org.apache.hadoop.hive.metastore.api.Function;
import org.apache.hadoop.hive.metastore.api.GetOpenTxnsInfoResponse;
import org.apache.hadoop.hive.metastore.api.GetPrincipalsInRoleRequest;
import org.apache.hadoop.hive.metastore.api.GetPrincipalsInRoleResponse;
import org.apache.hadoop.hive.metastore.api.GetRoleGrantsForPrincipalRequest;
import org.apache.hadoop.hive.metastore.api.GetRoleGrantsForPrincipalResponse;
import org.apache.hadoop.hive.metastore.api.HeartbeatTxnRangeResponse;
import org.apache.hadoop.hive.metastore.api.HiveObjectPrivilege;
import org.apache.hadoop.hive.metastore.api.HiveObjectRef;
import org.apache.hadoop.hive.metastore.api.Index;
import org.apache.hadoop.hive.metastore.api.InvalidInputException;
import org.apache.hadoop.hive.metastore.api.InvalidObjectException;
import org.apache.hadoop.hive.metastore.api.InvalidOperationException;
import org.apache.hadoop.hive.metastore.api.InvalidPartitionException;
import org.apache.hadoop.hive.metastore.api.LockRequest;
import org.apache.hadoop.hive.metastore.api.LockResponse;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.NoSuchLockException;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.metastore.api.NoSuchTxnException;
import org.apache.hadoop.hive.metastore.api.NotificationEventRequest;
import org.apache.hadoop.hive.metastore.api.NotificationEventResponse;
import org.apache.hadoop.hive.metastore.api.OpenTxnsResponse;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.PartitionEventType;
import org.apache.hadoop.hive.metastore.api.PartitionsStatsRequest;
import org.apache.hadoop.hive.metastore.api.PrincipalPrivilegeSet;
import org.apache.hadoop.hive.metastore.api.PrincipalType;
import org.apache.hadoop.hive.metastore.api.PrivilegeBag;
import org.apache.hadoop.hive.metastore.api.Role;
import org.apache.hadoop.hive.metastore.api.SetPartitionsStatsRequest;
import org.apache.hadoop.hive.metastore.api.ShowCompactResponse;
import org.apache.hadoop.hive.metastore.api.ShowLocksResponse;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.api.TableStatsRequest;
import org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore;
import org.apache.hadoop.hive.metastore.api.TxnAbortedException;
import org.apache.hadoop.hive.metastore.api.TxnOpenException;
import org.apache.hadoop.hive.metastore.api.UnknownDBException;
import org.apache.hadoop.hive.metastore.api.UnknownPartitionException;
import org.apache.hadoop.hive.metastore.api.UnknownTableException;
import org.apache.hadoop.hive.metastore.api.UnlockRequest;
import org.apache.hadoop.hive.metastore.partition.spec.PartitionSpecProxy;
import org.apache.hadoop.hive.shims.ShimLoader;
import org.apache.hadoop.hive.thrift.HadoopThriftAuthBridge;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.hadoop.hive.conf.HiveConfUtil.isEmbeddedMetaStore;

/**
 * Modified from apache hive  org.apache.hadoop.hive.metastore.HiveMetaStoreClient.java
 * Newly added method should cover hive0/1/2/3 metastore server.
 */
public class HiveMetaStoreClient implements IMetaStoreClient, AutoCloseable {
    private static final Logger LOG = LogManager.getLogger(HiveMetaStoreClient.class);

    ThriftHiveMetastore.Iface client = null;
    private TTransport transport = null;
    private boolean isConnected = false;
    private URI[] metastoreUris;
    protected final Configuration conf;
    // Keep a copy of HiveConf so if Session conf changes, we may need to get a new HMS client.
    private String tokenStrForm;
    private final boolean localMetaStore;
    private Map<String, String> currentMetaVars;

    private static final AtomicInteger connCount = new AtomicInteger(0);

    // for thrift connects
    private int retries = 5;
    private long retryDelaySeconds = 0;

    public HiveMetaStoreClient(Configuration conf) throws MetaException {
        this(conf, null, true);
    }

    public HiveMetaStoreClient(Configuration conf, HiveMetaHookLoader hookLoader) throws MetaException {
        this(conf, hookLoader, true);
    }

    public HiveMetaStoreClient(Configuration conf, HiveMetaHookLoader hookLoader, Boolean allowEmbedded)
            throws MetaException {

        if (conf == null) {
            conf = new Configuration();
            this.conf = conf;
        } else {
            this.conf = new Configuration(conf);
        }
        UserGroupInformation.setConfiguration(conf);
        UserGroupInformation.setLoginUser(null);

        String msUri = HiveConf.getVar(conf, ConfVars.METASTOREURIS);
        localMetaStore = isEmbeddedMetaStore(msUri);
        if (localMetaStore) {
            throw new MetaException("Embedded metastore is not allowed here. Please configure "
                    + ConfVars.METASTOREURIS.toString() + "; it is currently set to [" + msUri + "]");
        }

        // get the number retries
        retries = HiveConf.getIntVar(conf, ConfVars.METASTORETHRIFTCONNECTIONRETRIES);
        retryDelaySeconds = HiveConf.getTimeVar(conf,
                ConfVars.METASTORE_CLIENT_CONNECT_RETRY_DELAY, TimeUnit.SECONDS);

        // user wants file store based configuration
        if (HiveConf.getVar(conf, ConfVars.METASTOREURIS) != null) {
            resolveUris();
        } else {
            LOG.error("NOT getting uris from conf");
            throw new MetaException("MetaStoreURIs not found in conf file");
        }

        //If HADOOP_PROXY_USER is set in env or property,
        //then need to create metastore client that proxies as that user.
        String HADOOP_PROXY_USER = "HADOOP_PROXY_USER";
        String proxyUser = System.getenv(HADOOP_PROXY_USER);
        if (proxyUser == null) {
            proxyUser = System.getProperty(HADOOP_PROXY_USER);
        }
        //if HADOOP_PROXY_USER is set, create DelegationToken using real user
        if (proxyUser != null) {
            LOG.info(HADOOP_PROXY_USER + " is set. Using delegation "
                    + "token for HiveMetaStore connection.");
            try {
                UserGroupInformation.getLoginUser().getRealUser().doAs(
                        (PrivilegedExceptionAction<Void>) () -> {
                            open();
                            return null;
                        });
                String delegationTokenPropString = "DelegationTokenForHiveMetaStoreServer";
                String delegationTokenStr = getDelegationToken(proxyUser, proxyUser);
/*                SecurityUtils.setTokenStr(UserGroupInformation.getCurrentUser(), delegationTokenStr,
                        delegationTokenPropString);*/
                HiveConf.setVar(this.conf, ConfVars.METASTORE_CLUSTER_DELEGATION_TOKEN_STORE_ZK_CONNECTSTR, delegationTokenPropString);
                close();
            } catch (Exception e) {
                LOG.error("Error while setting delegation token for " + proxyUser, e);
                if (e instanceof MetaException) {
                    throw (MetaException) e;
                } else {
                    throw new MetaException(e.getMessage());
                }
            }
        }
        // finally open the store
        open();
    }

    private void resolveUris() throws MetaException {
        String[] metastoreUrisString = HiveConf.getVar(conf,
                ConfVars.METASTOREURIS).split(",");

        List<URI> metastoreURIArray = new ArrayList<URI>();
        try {
            for (String s : metastoreUrisString) {
                URI tmpUri = new URI(s);
                if (tmpUri.getScheme() == null) {
                    throw new IllegalArgumentException("URI: " + s
                            + " does not have a scheme");
                }
                metastoreURIArray.add(new URI(
                        tmpUri.getScheme(),
                        tmpUri.getUserInfo(),
                        tmpUri.getHost(),
                        tmpUri.getPort(),
                        tmpUri.getPath(),
                        tmpUri.getQuery(),
                        tmpUri.getFragment()));
            }
            metastoreUris = new URI[metastoreURIArray.size()];
            for (int j = 0; j < metastoreURIArray.size(); j++) {
                metastoreUris[j] = metastoreURIArray.get(j);
            }
        } catch (IllegalArgumentException e) {
            throw (e);
        } catch (Exception e) {
            MetaStoreUtils.logAndThrowMetaException(e);
        }
    }

    /**
     * Swaps the first element of the metastoreUris array with a random element from the
     * remainder of the array.
     */
    private void promoteRandomMetaStoreURI() {
        if (metastoreUris.length <= 1) {
            return;
        }
        int index = ThreadLocalRandom.current().nextInt(metastoreUris.length - 1) + 1;
        URI tmp = metastoreUris[0];
        metastoreUris[0] = metastoreUris[index];
        metastoreUris[index] = tmp;
    }

    @Override
    public boolean isCompatibleWith(HiveConf hiveConf) {
        return false;
    }

    @Override
    public void setHiveAddedJars(String addedJars) {
        HiveConf.setVar(conf, ConfVars.HIVEJAR, addedJars);
    }

    @Override
    public void reconnect() throws MetaException {
        if (localMetaStore) {
            // For direct DB connections we don't yet support reestablishing connections.
            throw new MetaException("For direct MetaStore DB connections, we don't support retries" +
                    " at the client level.");
        } else {
            close();
            // If the user passes in an address of 'hive.metastore.uris' similar to nginx, fe may only resolve to one url.
            // If the user's ip changes, thrift client can't use other url to access. Therefore, we need to resolve uris
            // for each reconnect. After all, reconnect is a rare behavior.
            resolveUris();
            open();
        }
    }

    private void open() throws MetaException {
        isConnected = false;
        TTransportException tte = null;
        boolean useSasl = HiveConf.getBoolVar(conf, ConfVars.METASTORE_USE_THRIFT_SASL);
        boolean useFramedTransport = HiveConf.getBoolVar(conf, ConfVars.METASTORE_USE_THRIFT_FRAMED_TRANSPORT);
        boolean useCompactProtocol = HiveConf.getBoolVar(conf, ConfVars.METASTORE_USE_THRIFT_COMPACT_PROTOCOL);
        int clientSocketTimeout = (int) HiveConf.getTimeVar(conf,
                ConfVars.METASTORE_CLIENT_SOCKET_TIMEOUT, TimeUnit.MILLISECONDS);

        for (int attempt = 0; !isConnected && attempt < retries; ++attempt) {
            for (URI store : metastoreUris) {
                LOG.info("Trying to connect to metastore with URI " + store);

                try {
                    transport = new TSocket(store.getHost(), store.getPort(), clientSocketTimeout);
                    if (useSasl) {
                        // Wrap thrift connection with SASL for secure connection.
                        try {
                            HadoopThriftAuthBridge.Client authBridge =
                                    new HadoopThriftAuthBridge.Client();
                            LOG.info(
                                    "HMSC::open(): Could not find delegation token. Creating KERBEROS-based thrift connection.");
                            String principalConfig =
                                    HiveConf.getVar(conf, ConfVars.METASTORE_KERBEROS_PRINCIPAL);
                            transport = authBridge.createClientTransport(
                                    principalConfig, store.getHost(), "KERBEROS", null,
                                    transport, ShimLoader.getHadoopThriftAuthBridge().getHadoopSaslProperties(conf));
                        } catch (IOException ioe) {
                            LOG.error("Couldn't create client transport", ioe);
                            throw new MetaException(ioe.toString());
                        }
                    } else {
                        if (useFramedTransport) {
                            transport = new TFramedTransport(transport);
                        }
                    }

                    final TProtocol protocol;
                    if (useCompactProtocol) {
                        protocol = new TCompactProtocol(transport);
                    } else {
                        protocol = new TBinaryProtocol(transport);
                    }
                    client = new ThriftHiveMetastore.Client(protocol);
                    try {
                        if (!transport.isOpen()) {
                            transport.open();
                            LOG.info("Opened a connection to metastore, current connections: " +
                                    connCount.incrementAndGet());
                        }
                        isConnected = true;
                    } catch (TTransportException e) {
                        tte = e;
                        if (LOG.isDebugEnabled()) {
                            LOG.warn("Failed to connect to the MetaStore Server...", e);
                        } else {
                            // Don't print full exception trace if DEBUG is not on.
                            LOG.warn("Failed to connect to the MetaStore Server...");
                        }
                    }

                    if (isConnected && !useSasl && HiveConf.getBoolVar(conf, ConfVars.METASTORE_EXECUTE_SET_UGI)) {
                        // Call set_ugi, only in unsecure mode.
                        try {
                            UserGroupInformation ugi = UserGroupInformation.getCurrentUser();
                            client.set_ugi(ugi.getUserName(), Arrays.asList(ugi.getGroupNames()));
                        } catch (IOException e) {
                            LOG.warn("Failed to find ugi of client set_ugi() is not successful, " +
                                    "Continuing without it.", e);
                        } catch (TException e) {
                            LOG.warn("set_ugi() not successful, Likely cause: new client talking to old server. "
                                    + "Continuing without it.", e);
                        }
                    }
                } catch (MetaException e) {
                    LOG.error("Unable to connect to metastore with URI " + store
                            + " in attempt " + attempt, e);
                }
                if (isConnected) {
                    break;
                }
            }
            // Wait before launching the next round of connection retries.
            if (!isConnected && retryDelaySeconds > 0) {
                try {
                    LOG.info("Waiting " + retryDelaySeconds + " seconds before next connection attempt.");
                    Thread.sleep(retryDelaySeconds * 1000);
                } catch (InterruptedException ignore) {
                }
            }
        }

        if (!isConnected) {
            throw new MetaException("Could not connect to meta store using any of the URIs provided." +
                    " Most recent failure: " + StringUtils.stringifyException(tte));
        }

        LOG.info("Connected to metastore.");
    }

    @Override
    public void close() {
        isConnected = false;
        currentMetaVars = null;
        try {
            if (null != client) {
                client.shutdown();
            }
        } catch (TException e) {
            LOG.debug("Unable to shutdown metastore client. Will try closing transport directly.", e);
        }
        // Transport would have got closed via client.shutdown(), so we dont need this, but
        // just in case, we make this call.
        if ((transport != null) && transport.isOpen()) {
            transport.close();
            LOG.info("Closed a connection to metastore, current connections: " + connCount.decrementAndGet());
        }
    }

    @Override
    public Table getTable(String dbName, String tableName) throws MetaException, TException, NoSuchObjectException {
        try {
            // Using get_table() first, if user's Hive forbidden this request,
            // then fail over to use get_table_req() instead.
            return client.get_table(dbName, tableName);
        } catch (NoSuchObjectException e) {
            // NoSuchObjectException need to be thrown when creating iceberg table.
            LOG.warn("Failed to get table {}.{}", dbName, tableName, e);
            throw e;
        } catch (Exception e) {
            LOG.warn("Using get_table() failed, fail over to use get_table_req()", e);
            throw e;
        }
    }

    @Override
    public Table getTableByView(String s, String s1, String s2, String s3) throws MetaException, TException, NoSuchObjectException {
        return null;
    }

    @Override
    public Partition getPartition(String dbName, String tblName, List<String> partVals)
            throws NoSuchObjectException, MetaException, TException {
        return client.get_partition(dbName, tblName, partVals);
    }

    @Override
    public Partition getPartition(String dbName, String tblName, String name)
            throws MetaException, UnknownTableException, NoSuchObjectException, TException {
        return client.get_partition_by_name(dbName, tblName, name);
    }

    @Override
    public List<Partition> getPartitionsByNamesByView(String s, String s1, List<String> list, String s2, String s3) throws NoSuchObjectException, MetaException, TException {
        return null;
    }

    @Override
    public List<String> listPartitionNames(String dbName, String tblName, short maxParts)
            throws NoSuchObjectException, MetaException, TException {
        return client.get_partition_names(dbName, tblName, shrinkMaxtoShort(maxParts));
    }

    @Override
    public List<String> listPartitionNamesByView(String s, String s1, short i, String s2, String s3) throws MetaException, TException {
        return null;
    }

    @Override
    public List<String> listPartitionNames(String dbName, String tblName, List<String> partVals, short maxParts)
            throws MetaException, TException, NoSuchObjectException {
        return client.get_partition_names_ps(dbName, tblName, partVals, shrinkMaxtoShort(maxParts));
    }

    @Override
    public List<Partition> listPartitionsWithAuthInfoByView(String s, String s1, List<String> list, short i, String s2, List<String> list1, String s3, String s4) throws NoSuchObjectException, MetaException, TException {
        return null;
    }

    @Override
    public List<String> partitionNameToVals(String name) throws MetaException, TException {
        return client.partition_name_to_vals(name);
    }

    private short shrinkMaxtoShort(int max) {
        if (max < 0) {
            return -1;
        } else if (max <= Short.MAX_VALUE) {
            return (short) max;
        } else {
            return Short.MAX_VALUE;
        }
    }

    @Override
    public List<Partition> getPartitionsByNames(String dbName, String tblName,
                                                List<String> partNames) throws TException {
        return client.get_partitions_by_names(dbName, tblName, partNames);
    }

    @Override
    public List<Partition> listPartitionsWithAuthInfoByView(String s, String s1, short i, String s2, List<String> list, String s3, String s4) throws NoSuchObjectException, MetaException, TException {
        return null;
    }

    @Override
    public List<ColumnStatisticsObj> getTableColumnStatistics(String dbName, String tableName,
                                                              List<String> colNames) throws TException {
        TableStatsRequest rqst = new TableStatsRequest(dbName, tableName, colNames);
        return client.get_table_statistics_req(rqst).getTableStats();
    }

    @Override
    public List<ColumnStatisticsObj> getTableColumnStatisticsByView(String s, String s1, List<String> list, String s2, String s3) throws NoSuchObjectException, MetaException, TException, InvalidInputException, InvalidObjectException {
        return null;
    }

    @Override
    public Map<String, List<ColumnStatisticsObj>> getPartitionColumnStatistics(
            String dbName, String tableName, List<String> partNames, List<String> colNames)
            throws TException {
        PartitionsStatsRequest rqst = new PartitionsStatsRequest(dbName, tableName, colNames,
                partNames);
        return client.get_partitions_statistics_req(rqst).getPartStats();    }

    @Override
    public Map<String, List<ColumnStatisticsObj>> getPartitionColumnStatisticsByView(String s, String s1, List<String> list, List<String> list1, String s2, String s3) throws NoSuchObjectException, MetaException, TException {
        return null;
    }

    @Override
    public NotificationEventResponse getNextNotification(long lastEventId, int maxEvents, NotificationFilter filter)
            throws TException {
        NotificationEventRequest eventRequest = new NotificationEventRequest();
        eventRequest.setMaxEvents(maxEvents);
        eventRequest.setLastEvent(lastEventId);
        return client.get_next_notification(eventRequest);
    }

    @Override
    public CurrentNotificationEventId getCurrentNotificationEventId() throws TException {
        return client.get_current_notificationEventId();
    }

    public void setMetaConf(String key, String value) throws MetaException, TException {
        client.setMetaConf(key, value);
    }

    @Override
    public String getMetaConf(String key) throws MetaException, TException {
        return client.getMetaConf(key);
    }

    @Override
    public List<String> getDatabases(String databasePattern) throws MetaException, TException {
        return client.get_databases(databasePattern);
    }

    @Override
    public List<String> getAllDatabases() throws MetaException, TException {
        return client.get_all_databases();
    }

    @Override
    public List<String> getTables(String dbName, String tablePattern)
            throws MetaException, TException, UnknownDBException {
        throw new TException("method not implemented");
    }

    @Override
    public List<String> getAllTables(String dbName) throws MetaException, TException, UnknownDBException {
        return client.get_all_tables(dbName);
    }

    @Override
    public List<String> listTableNamesByFilter(String dbName, String filter, short maxTables)
            throws TException, InvalidOperationException, UnknownDBException {
        throw new TException("method not implemented");
    }

    @Override
    public void dropTable(String dbname, String name, boolean deleteData,
                          boolean ignoreUnknownTab) throws MetaException, TException,
            NoSuchObjectException, UnsupportedOperationException {
        //dropTable(getDefaultCatalog(conf), dbname, name, deleteData, ignoreUnknownTab, null);
    }

    @Override
    public void dropTable(String dbname, String name, boolean deleteData,
                          boolean ignoreUnknownTab, boolean ifPurge) throws TException {
        //dropTable(getDefaultCatalog(conf), dbname, name, deleteData, ignoreUnknownTab, ifPurge);
    }

    @Override
    public void dropTable(String s, boolean b) throws MetaException, UnknownTableException, TException, NoSuchObjectException {

    }

    @Override
    public void dropTable(String dbname, String name) throws TException {
        //dropTable(getDefaultCatalog(conf), dbname, name, true, true, null);
    }

    public void dropTable(String catName, String dbName, String tableName, boolean deleteData,
                          boolean ignoreUnknownTable, boolean ifPurge) throws TException {
        //build new environmentContext with ifPurge;
        EnvironmentContext envContext = null;
        if(ifPurge){
            Map<String, String> warehouseOptions;
            warehouseOptions = new HashMap<>();
            warehouseOptions.put("ifPurge", "TRUE");
            envContext = new EnvironmentContext(warehouseOptions);
        }
        //dropTable(dbName, tableName, deleteData, ignoreUnknownTable, envContext);

    }

    /**
     * Drop the table and choose whether to: delete the underlying table data;
     * throw if the table doesn't exist; save the data in the trash.
     *
     * @param catName catalog name
     * @param dbname database name
     * @param name table name
     * @param deleteData
     *          delete the underlying data or just delete the table in metadata
     * @param ignoreUnknownTab
     *          don't throw if the requested table doesn't exist
     * @param envContext
     *          for communicating with thrift
     * @throws MetaException
     *           could not drop table properly
     * @throws NoSuchObjectException
     *           the table wasn't found
     * @throws TException
     *           a thrift communication error occurred
     * @throws UnsupportedOperationException
     *           dropping an index table is not allowed
     * @see org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore.Iface#drop_table(java.lang.String,
     *      java.lang.String, boolean)
     */
    public void dropTable(String catName, String dbname, String name, boolean deleteData,
                          boolean ignoreUnknownTab, EnvironmentContext envContext) throws MetaException, TException,
            NoSuchObjectException, UnsupportedOperationException {
        Table tbl;
        try {
            tbl = getTable(dbname, name);
        } catch (NoSuchObjectException e) {
            if (!ignoreUnknownTab) {
                throw e;
            }
            return;
        }
        HiveMetaHook hook = getHook(tbl);
        if (hook != null) {
            hook.preDropTable(tbl);
        }
        boolean success = false;
        try {
            drop_table_with_environment_context(dbname, name, deleteData, envContext);
            if (hook != null) {
                hook.commitDropTable(tbl, deleteData || (envContext != null && "TRUE".equals(envContext.getProperties().get("ifPurge"))));
            }
            success=true;
        } catch (NoSuchObjectException e) {
            if (!ignoreUnknownTab) {
                throw e;
            }
        } finally {
            if (!success && (hook != null)) {
                hook.rollbackDropTable(tbl);
            }
        }
    }

    @Override
    public boolean tableExists(String databaseName, String tableName)
            throws MetaException, TException, UnknownDBException {
        throw new TException("method not implemented");
    }

    @Override
    public boolean tableExistsByView(String s, String s1, String s2, String s3) throws MetaException, TException, UnknownDBException {
        return false;
    }

    @Override
    public boolean tableExists(String s) throws MetaException, TException, UnknownDBException {
        return false;
    }

    @Override
    public Table getTable(String s) throws MetaException, TException, NoSuchObjectException {
        return null;
    }

    @Override
    public boolean listPartitionsByExprByView(String s, String s1, byte[] bytes, String s2, short i, List<Partition> list, String s3, String s4) throws TException {
        return false;
    }

    @Override
    public Database getDatabase(String databaseName) throws NoSuchObjectException, MetaException, TException {
        return client.get_database(databaseName);
    }

    @Override
    public Database getDatabaseByView(String s, String s1, String s2) throws NoSuchObjectException, MetaException, TException {
        return null;
    }

    @Override
    public Partition getPartitionWithAuthInfoByView(String s, String s1, List<String> list, String s2, List<String> list1, String s3, String s4) throws MetaException, UnknownTableException, NoSuchObjectException, TException {
        return null;
    }

    @Override
    public List<Table> getTableObjectsByName(String dbName, List<String> tableNames)
            throws MetaException, InvalidOperationException, UnknownDBException, TException {
        return client.get_table_objects_by_name(dbName, tableNames);
    }

    @Override
    public Partition appendPartition(String dbName, String tableName, List<String> partVals)
            throws InvalidObjectException, AlreadyExistsException, MetaException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public Partition appendPartition(String dbName, String tableName, String name)
            throws InvalidObjectException, AlreadyExistsException, MetaException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public Partition add_partition(Partition partition)
            throws InvalidObjectException, AlreadyExistsException, MetaException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public int add_partitions(List<Partition> partitions)
            throws InvalidObjectException, AlreadyExistsException, MetaException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public int add_partitions_pspec(PartitionSpecProxy partitionSpec)
            throws InvalidObjectException, AlreadyExistsException, MetaException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public List<Partition> add_partitions(List<Partition> partitions, boolean ifNotExists, boolean needResults)
            throws InvalidObjectException, AlreadyExistsException, MetaException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public Partition exchange_partition(Map<String, String> partitionSpecs, String sourceDb, String sourceTable,
                                        String destdb, String destTableName)
            throws MetaException, NoSuchObjectException, InvalidObjectException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public Partition getPartitionWithAuthInfo(String dbName, String tableName, List<String> pvals, String userName,
                                              List<String> groupNames)
            throws MetaException, UnknownTableException, NoSuchObjectException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public List<Partition> listPartitions(String db_name, String tbl_name, short max_parts)
            throws NoSuchObjectException, MetaException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public List<Partition> listPartitionsByView(String s, String s1, short i, String s2, String s3) throws NoSuchObjectException, MetaException, TException {
        return null;
    }

    @Override
    public PartitionSpecProxy listPartitionSpecs(String dbName, String tableName, int maxParts) throws TException {
        throw new TException("method not implemented");
    }

    @Override
    public PartitionSpecProxy listPartitionSpecsByView(String s, String s1, int i, String s2, String s3) throws TException {
        return null;
    }

    @Override
    public List<Partition> listPartitions(String db_name, String tbl_name, List<String> part_vals, short max_parts)
            throws NoSuchObjectException, MetaException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public List<Partition> listPartitionsByFilter(String db_name, String tbl_name, String filter, short max_parts)
            throws MetaException, NoSuchObjectException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public List<Partition> listPartitionsByFilterByView(String s, String s1, String s2, short i, String s3, String s4) throws MetaException, NoSuchObjectException, TException {
        return null;
    }

    @Override
    public PartitionSpecProxy listPartitionSpecsByFilter(String db_name, String tbl_name, String filter, int max_parts)
            throws MetaException, NoSuchObjectException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public PartitionSpecProxy listPartitionSpecsByFilterByView(String s, String s1, String s2, int i, String s3, String s4) throws MetaException, NoSuchObjectException, TException {
        return null;
    }

    @Override
    public boolean listPartitionsByExpr(String db_name, String tbl_name, byte[] expr, String default_partition_name,
                                        short max_parts, List<Partition> result) throws TException {
        throw new TException("method not implemented");
    }

    @Override
    public List<Partition> listPartitionsByView(String s, String s1, List<String> list, short i, String s2, String s3) throws NoSuchObjectException, MetaException, TException {
        return null;
    }

    @Override
    public List<Partition> listPartitionsWithAuthInfo(String dbName, String tableName, short maxParts, String userName,
                                                      List<String> groupNames)
            throws MetaException, TException, NoSuchObjectException {
        throw new TException("method not implemented");
    }

    @Override
    public Partition getPartitionByView(String s, String s1, List<String> list, String s2, String s3) throws NoSuchObjectException, MetaException, TException {
        return null;
    }

    @Override
    public List<Partition> listPartitionsWithAuthInfo(String dbName, String tableName, List<String> partialPvals,
                                                      short maxParts, String userName, List<String> groupNames)
            throws MetaException, TException, NoSuchObjectException {
        throw new TException("method not implemented");
    }

    @Override
    public void markPartitionForEvent(String db_name, String tbl_name, Map<String, String> partKVs,
                                      PartitionEventType eventType)
            throws MetaException, NoSuchObjectException, TException, UnknownTableException, UnknownDBException,
            UnknownPartitionException, InvalidPartitionException {
        throw new TException("method not implemented");

    }

    @Override
    public boolean isPartitionMarkedForEvent(String db_name, String tbl_name, Map<String, String> partKVs,
                                             PartitionEventType eventType)
            throws MetaException, NoSuchObjectException, TException, UnknownTableException, UnknownDBException,
            UnknownPartitionException, InvalidPartitionException {
        throw new TException("method not implemented");
    }

    @Override
    public void validatePartitionNameCharacters(List<String> partVals) throws TException, MetaException {
        throw new TException("method not implemented");

    }

    private HiveMetaHook getHook(Table tbl) {
        return null;
    }


    @Override
    public void createTable(Table tbl) throws TException {
        createTable(tbl, null);
    }

    public void createTable(Table tbl, EnvironmentContext envContext) throws AlreadyExistsException,
            InvalidObjectException, MetaException, NoSuchObjectException, TException {
        HiveMetaHook hook = getHook(tbl);
        if (hook != null) {
            hook.preCreateTable(tbl);
        }
        boolean success = false;
        try {
            // Subclasses can override this step (for example, for temporary tables)
            create_table_with_environment_context(tbl, envContext);
            if (hook != null) {
                hook.commitCreateTable(tbl);
            }
            success = true;
        }
        finally {
            if (!success && (hook != null)) {
                try {
                    hook.rollbackCreateTable(tbl);
                } catch (Exception e){
                    LOG.error("Create rollback failed with", e);
                }
            }
        }
    }

    protected void create_table_with_environment_context(Table tbl, EnvironmentContext envContext)
            throws AlreadyExistsException, InvalidObjectException,
            MetaException, NoSuchObjectException, TException {
        client.create_table_with_environment_context(tbl, envContext);
    }

    protected void drop_table_with_environment_context(String dbname, String name,
                                                       boolean deleteData, EnvironmentContext envContext) throws TException {
        client.drop_table_with_environment_context(dbname,
                name, deleteData, envContext);
    }

    @Override
    public void alter_table(String databaseName, String tblName, Table table)
            throws InvalidOperationException, MetaException, TException {
        throw new TException("method not implemented");

    }

    @Override
    public void alter_table(String defaultDatabaseName, String tblName, Table table, boolean cascade)
            throws InvalidOperationException, MetaException, TException {
        throw new TException("method not implemented");
    }

    public void alter_table_with_environmentContext(String databaseName, String tblName, Table table,
                                                    EnvironmentContext environmentContext)
            throws TException {
        client.alter_table_with_environment_context(databaseName, tblName, table, environmentContext);
    }

    @Override
    public void createDatabase(Database db)
            throws InvalidObjectException, AlreadyExistsException, MetaException, TException {
        client.create_database(db);
    }

    /**
     * @param name
     * @throws NoSuchObjectException
     * @throws InvalidOperationException
     * @throws MetaException
     * @throws TException
     * @see org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore.Iface#drop_database(java.lang.String, boolean, boolean)
     */
    @Override
    public void dropDatabase(String name)
            throws NoSuchObjectException, InvalidOperationException, MetaException, TException {
        dropDatabase(name, true, false, false);
    }

    @Override
    public void dropDatabase(String name, boolean deleteData, boolean ignoreUnknownDb)
            throws NoSuchObjectException, InvalidOperationException, MetaException, TException {
        dropDatabase(name, deleteData, ignoreUnknownDb, false);
    }

    @Override
    public void dropDatabase(String dbName, boolean deleteData, boolean ignoreUnknownDb, boolean cascade)
            throws NoSuchObjectException, InvalidOperationException, MetaException, TException {
        try {
            getDatabase(dbName);
        } catch (NoSuchObjectException e) {
            if (!ignoreUnknownDb) {
                throw e;
            }
            return;
        }

        if (cascade) {
            // Note that this logic may drop some of the tables of the database
            // even if the drop database fail for any reason
            // TODO: Fix this
            List<String> materializedViews = getTables(dbName, ".*");
            for (String table : materializedViews) {
                // First we delete the materialized views
                dropTable(dbName, table, deleteData, true);
            }
            List<String> tableList = getAllTables(dbName);
            for (String table : tableList) {
                // Now we delete the rest of tables
                try {
                    // Subclasses can override this step (for example, for temporary tables)
                    dropTable(dbName, table, deleteData, true);
                } catch (UnsupportedOperationException e) {
                    // Ignore Index tables, those will be dropped with parent tables
                }
            }
        }
        client.drop_database(dbName, deleteData, cascade);
    }

    @Override
    public void alterDatabase(String name, Database db) throws NoSuchObjectException, MetaException, TException {
        throw new TException("method not implemented");

    }

    @Override
    public boolean dropPartition(String db_name, String tbl_name, List<String> part_vals, boolean deleteData)
            throws NoSuchObjectException, MetaException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public boolean dropPartition(String db_name, String tbl_name, List<String> part_vals, PartitionDropOptions options)
            throws NoSuchObjectException, MetaException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public List<Partition> dropPartitions(String s, String s1, List<ObjectPair<Integer, byte[]>> list, boolean b, boolean b1, boolean b2, boolean b3) throws NoSuchObjectException, MetaException, TException {
        return null;
    }

    @Override
    public List<Partition> dropPartitions(String dbName, String tblName, List<ObjectPair<Integer, byte[]>> partExprs,
                                          boolean deleteData, boolean ifExists, boolean needResults)
            throws NoSuchObjectException, MetaException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public List<Partition> dropPartitions(String dbName, String tblName, List<ObjectPair<Integer, byte[]>> partExprs,
                                          PartitionDropOptions options)
            throws NoSuchObjectException, MetaException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public boolean dropPartition(String db_name, String tbl_name, String name, boolean deleteData)
            throws NoSuchObjectException, MetaException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public List<String> listPartitionNamesByView(String s, String s1, List<String> list, short i, String s2, String s3) throws MetaException, TException, NoSuchObjectException {
        return null;
    }

    @Override
    public void alter_partition(String dbName, String tblName, Partition newPart)
            throws InvalidOperationException, MetaException, TException {
        throw new TException("method not implemented");

    }

    @Override
    public void alter_partitions(String dbName, String tblName, List<Partition> newParts)
            throws InvalidOperationException, MetaException, TException {
        throw new TException("method not implemented");

    }

    @Override
    public void renamePartition(String dbname, String tableName, List<String> part_vals, Partition newPart)
            throws InvalidOperationException, MetaException, TException {
        throw new TException("method not implemented");

    }

    @Override
    public List<FieldSchema> getFields(String db, String tableName)
            throws MetaException, TException, UnknownTableException, UnknownDBException {
        throw new TException("method not implemented");
    }

    @Override
    public List<FieldSchema> getSchema(String db, String tableName)
            throws MetaException, TException, UnknownTableException, UnknownDBException {
        throw new TException("method not implemented");
    }

    @Override
    public List<FieldSchema> getSchemaByView(String s, String s1, String s2, String s3) throws MetaException, TException, UnknownTableException, UnknownDBException {
        return null;
    }

    @Override
    public String getConfigValue(String name, String defaultValue) throws TException, ConfigValSecurityException {
        throw new TException("method not implemented");
    }

    @Override
    public Partition getPartitionByView(String s, String s1, String s2, String s3, String s4) throws MetaException, TException, UnknownTableException, NoSuchObjectException {
        return null;
    }

    @Override
    public Map<String, String> partitionNameToSpec(String name) throws MetaException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public List<FieldSchema> getFieldsByView(String s, String s1, String s2, String s3) throws MetaException, TException, UnknownTableException, UnknownDBException {
        return null;
    }

    @Override
    public void createIndex(Index index, Table table) throws InvalidObjectException, MetaException, NoSuchObjectException, TException, AlreadyExistsException {

    }

    @Override
    public void alter_index(String s, String s1, String s2, Index index) throws InvalidOperationException, MetaException, TException {

    }

    @Override
    public Index getIndex(String s, String s1, String s2) throws MetaException, UnknownTableException, NoSuchObjectException, TException {
        return null;
    }

    @Override
    public List<String> listIndexNamesByView(String s, String s1, short i, String s2, String s3) throws MetaException, TException {
        return null;
    }

    @Override
    public List<Index> listIndexes(String s, String s1, short i) throws NoSuchObjectException, MetaException, TException {
        return null;
    }

    @Override
    public Index getIndexByView(String s, String s1, String s2, String s3, String s4) throws MetaException, UnknownTableException, NoSuchObjectException, TException {
        return null;
    }

    @Override
    public List<String> listIndexNames(String s, String s1, short i) throws MetaException, TException {
        return null;
    }

    @Override
    public boolean dropIndex(String s, String s1, String s2, boolean b) throws NoSuchObjectException, MetaException, TException {
        return false;
    }

    @Override
    public List<Index> listIndexesByView(String s, String s1, short i, String s2, String s3) throws NoSuchObjectException, MetaException, TException {
        return null;
    }

    @Override
    public boolean updateTableColumnStatistics(ColumnStatistics statsObj)
            throws NoSuchObjectException, InvalidObjectException, MetaException, TException, InvalidInputException {
        throw new TException("method not implemented");
    }

    @Override
    public boolean updatePartitionColumnStatistics(ColumnStatistics statsObj)
            throws NoSuchObjectException, InvalidObjectException, MetaException, TException, InvalidInputException {
        throw new TException("method not implemented");
    }

    @Override
    public boolean deletePartitionColumnStatistics(String dbName, String tableName, String partName, String colName)
            throws NoSuchObjectException, MetaException, InvalidObjectException, TException, InvalidInputException {
        throw new TException("method not implemented");
    }

    @Override
    public boolean deleteTableColumnStatistics(String dbName, String tableName, String colName)
            throws NoSuchObjectException, MetaException, InvalidObjectException, TException, InvalidInputException {
        throw new TException("method not implemented");
    }

    @Override
    public boolean create_role(Role role) throws MetaException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public boolean drop_role(String role_name) throws MetaException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public List<String> listRoleNames() throws MetaException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public boolean grant_role(String role_name, String user_name, PrincipalType principalType, String grantor,
                              PrincipalType grantorType, boolean grantOption) throws MetaException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public boolean revoke_role(String role_name, String user_name, PrincipalType principalType, boolean grantOption)
            throws MetaException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public List<Role> list_roles(String principalName, PrincipalType principalType) throws MetaException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public PrincipalPrivilegeSet get_privilege_set(HiveObjectRef hiveObject, String user_name, List<String> group_names)
            throws MetaException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public List<HiveObjectPrivilege> list_privileges(String principal_name, PrincipalType principal_type,
                                                     HiveObjectRef hiveObject) throws MetaException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public boolean grant_privileges(PrivilegeBag privileges) throws MetaException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public boolean revoke_privileges(PrivilegeBag privileges, boolean grantOption) throws MetaException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public String getDelegationToken(String owner, String renewerKerberosPrincipalName)
            throws MetaException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public long renewDelegationToken(String tokenStrForm) throws MetaException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public void cancelDelegationToken(String tokenStrForm) throws MetaException, TException {
        throw new TException("method not implemented");

    }

    @Override
    public String getTokenStrForm() throws IOException {
        return tokenStrForm;
    }

    @Override
    public ByteBuffer getPathToken(List<AccessEntry> list) throws MetaException, TException {
        return null;
    }

    @Override
    public void createFunction(Function func) throws InvalidObjectException, MetaException, TException {
        throw new TException("method not implemented");

    }

    @Override
    public void alterFunction(String dbName, String funcName, Function newFunction)
            throws InvalidObjectException, MetaException, TException {
        throw new TException("method not implemented");

    }

    @Override
    public void dropFunction(String dbName, String funcName)
            throws MetaException, NoSuchObjectException, InvalidObjectException, InvalidInputException, TException {
        throw new TException("method not implemented");

    }

    @Override
    public Function getFunction(String dbName, String funcName) throws MetaException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public List<String> getFunctions(String dbName, String pattern) throws MetaException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public ValidTxnList getValidTxns() throws TException {
        throw new TException("method not implemented");
    }

    @Override
    public ValidTxnList getValidTxns(long currentTxn) throws TException {
        throw new TException("method not implemented");
    }

    @Override
    public long openTxn(String user) throws TException {
        throw new TException("method not implemented");
    }

    @Override
    public OpenTxnsResponse openTxns(String user, int numTxns) throws TException {
        throw new TException("method not implemented");
    }

    @Override
    public void rollbackTxn(long txnid) throws NoSuchTxnException, TException {
        throw new TException("method not implemented");

    }

    @Override
    public void commitTxn(long txnid) throws NoSuchTxnException, TxnAbortedException, TException {
        throw new TException("method not implemented");

    }

    @Override
    public GetOpenTxnsInfoResponse showTxns() throws TException {
        throw new TException("method not implemented");
    }

    @Override
    public LockResponse lock(LockRequest request) throws NoSuchTxnException, TxnAbortedException, TException {
        return client.lock(request);
    }

    @Override
    public LockResponse checkLock(long lockid)
            throws NoSuchTxnException, TxnAbortedException, NoSuchLockException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public void unlock(long lockid) throws NoSuchLockException, TxnOpenException, TException {
        client.unlock(new UnlockRequest(lockid));
    }

    @Override
    public ShowLocksResponse showLocks() throws TException {
        throw new TException("method not implemented");
    }

    @Override
    public void heartbeat(long txnid, long lockid)
            throws NoSuchLockException, NoSuchTxnException, TxnAbortedException, TException {
        throw new TException("method not implemented");

    }

    @Override
    public HeartbeatTxnRangeResponse heartbeatTxnRange(long min, long max) throws TException {
        throw new TException("method not implemented");
    }

    @Override
    public void compact(String dbname, String tableName, String partitionName, CompactionType type) throws TException {
        throw new TException("method not implemented");

    }

    @Override
    public ShowCompactResponse showCompactions() throws TException {
        throw new TException("method not implemented");
    }

    @Override
    public void addDynamicPartitions(long l, String s, String s1, List<String> list) throws TException {

    }

    @Override
    public FireEventResponse fireListenerEvent(FireEventRequest request) throws TException {
        throw new TException("method not implemented");
    }

    @Override
    public GetPrincipalsInRoleResponse get_principals_in_role(GetPrincipalsInRoleRequest getPrincRoleReq)
            throws MetaException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public GetRoleGrantsForPrincipalResponse get_role_grants_for_principal(
            GetRoleGrantsForPrincipalRequest getRolePrincReq) throws MetaException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public AggrStats getAggrColStatsFor(String dbName, String tblName, List<String> colNames, List<String> partName)
            throws NoSuchObjectException, MetaException, TException {
        throw new TException("method not implemented");
    }

    @Override
    public boolean setPartitionColumnStatistics(SetPartitionsStatsRequest request)
            throws NoSuchObjectException, InvalidObjectException, MetaException, TException, InvalidInputException {
        throw new TException("method not implemented");
    }
}

