/**
 * Copyright 2014 Cloudera Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kitesdk.minicluster;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.HiveMetaStore;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.IHMSHandler;
import org.apache.hadoop.hive.metastore.TSetIpAddressProcessor;
import org.apache.hadoop.hive.metastore.TUGIBasedProcessor;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.thrift.TUGIContainingTransport;
import org.apache.thrift.TProcessor;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TServerTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.apache.thrift.transport.TTransportFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class HiveService implements Service {

  private static final Logger LOG = LoggerFactory
      .getLogger(HiveService.class);

  /**
   * Service registration for MiniCluster factory
   */
  static {
    MiniCluster.registerService(HiveService.class);
  }

  private static final int CONNECTION_TIMEOUT = 30000;

  /**
   * Service configuration keys
   */

  /**
   * Configuration settings
   */
  private Configuration hadoopConf;
  private String workDir;
  private String bindIP = "127.0.0.1";
  private int port = 9083;
  private boolean clean = false;

  private Map<String, String> sysProps = Maps.newHashMap();
  private ExecutorService executorService;
  private TServer tServer;

  public HiveService() {
  }

  @Override
  public void configure(ServiceConfig serviceConfig) {
    this.workDir = serviceConfig.get(MiniCluster.WORK_DIR_KEY);
    if (serviceConfig.contains(MiniCluster.BIND_IP_KEY)) {
      bindIP = serviceConfig.get(MiniCluster.BIND_IP_KEY);
    }
    if (serviceConfig.contains(MiniCluster.CLEAN_KEY)) {
      clean = Boolean.parseBoolean(serviceConfig.get(MiniCluster.CLEAN_KEY));
    }
    if (serviceConfig.contains(MiniCluster.HIVE_METASTORE_PORT)) {
      port = Integer.parseInt(serviceConfig.get(MiniCluster.HIVE_METASTORE_PORT));
    }
    hadoopConf = serviceConfig.getHadoopConf();
  }

  @Override
  public Configuration getHadoopConf() {
    return hadoopConf;
  }

  @Override
  public void start() throws IOException {
    Preconditions.checkState(workDir != null,
        "The work dir must be set before starting cluster.");

    if (hadoopConf == null) {
      hadoopConf = new Configuration();
    }

    String localHiveLocation = getHiveLocation(workDir);
    if (clean) {
      LOG.info("Cleaning Hive cluster data at: " + localHiveLocation
          + " and starting fresh.");
      File file = new File(localHiveLocation);
      FileUtils.deleteDirectory(file);
    }

    HiveConf serverConf = configureHive(hadoopConf, localHiveLocation);

    executorService = Executors.newSingleThreadExecutor();
    tServer = startMetaStore(bindIP, port, serverConf);

    String serverHostname;
    if (bindIP.equals("0.0.0.0")) {
      serverHostname = "localhost";
    } else {
      serverHostname = bindIP;
    }
    if (!waitForServerUp(serverConf, serverHostname, port, CONNECTION_TIMEOUT)) {
      throw new IOException("Waiting for startup of standalone server");
    }

    LOG.info("Hive Minicluster service started.");
  }

  @Override
  public void stop() throws IOException {
    resetSystemProperties();
    if (tServer != null) {
      tServer.stop();
    }
    LOG.info("Hive Minicluster service shut down.");
    tServer = null;
    hadoopConf = null;
  }

  @Override
  public List<Class<? extends Service>> dependencies() {
    // no dependencies
    return null;
  }

  private HiveConf configureHive(Configuration conf, String localHiveLocation) throws IOException {
    conf.set("hive.metastore.local", "false");
    conf.set("hive.metastore.uris", "thrift://" + bindIP + ":" + port);
    File localHiveDir = new File(localHiveLocation);
    localHiveDir.mkdirs();
    File metastoreDbDir = new File(localHiveDir, "metastore_db");
    conf.set(HiveConf.ConfVars.METASTORECONNECTURLKEY.varname,
        "jdbc:derby:" + metastoreDbDir.getPath() + ";create=true");
    File derbyLogFile = new File(localHiveDir, "derby.log");
    derbyLogFile.createNewFile();
    setSystemProperty("derby.stream.error.file", derbyLogFile.getPath());
    return new HiveConf(conf, this.getClass());
  }

  private boolean waitForServerUp(HiveConf serverConf, String hostname, int port, int timeout) {
    long start = System.currentTimeMillis();
    while (true) {
      try {
        new HiveMetaStoreClient(serverConf);
        return true;
      } catch (MetaException e) {
        // ignore as this is expected
        LOG.info("server " + hostname + ":" + port + " not up " + e);
      }

      if (System.currentTimeMillis() > start + timeout) {
        break;
      }
      try {
        Thread.sleep(250);
      } catch (InterruptedException e) {
        // ignore
      }
    }
    return false;
  }

  private void setSystemProperty(String name, String value) {
    if (!sysProps.containsKey(name)) {
      String currentValue = System.getProperty(name);
      sysProps.put(name, currentValue);
    }
    if (value != null) {
      System.setProperty(name, value);
    }
    else {
      System.getProperties().remove(name);
    }
  }

  private void resetSystemProperties() {
    for (Map.Entry<String, String> entry : sysProps.entrySet()) {
      if (entry.getValue() != null) {
        System.setProperty(entry.getKey(), entry.getValue());
      }
      else {
        System.getProperties().remove(entry.getKey());
      }
    }
    sysProps.clear();
  }

  private static String getHiveLocation(String baseLocation) {
    return baseLocation + Path.SEPARATOR + "hive";
  }

  // XXX: From org.apache.hadoop.hive.metastore.HiveMetaStore,
  // with changes to support binding to a specified IP address (not only 0.0.0.0)

  private static final class ChainedTTransportFactory extends TTransportFactory {
    private final TTransportFactory parentTransFactory;
    private final TTransportFactory childTransFactory;

    private ChainedTTransportFactory(
        TTransportFactory parentTransFactory,
        TTransportFactory childTransFactory) {
      this.parentTransFactory = parentTransFactory;
      this.childTransFactory = childTransFactory;
    }

    @Override
    public TTransport getTransport(TTransport trans) {
      return childTransFactory.getTransport(parentTransFactory.getTransport(trans));
    }
  }

  private static final class TServerSocketKeepAlive extends TServerSocket {
    public TServerSocketKeepAlive(int port) throws TTransportException {
      super(port, 0);
    }

    public TServerSocketKeepAlive(InetSocketAddress address) throws TTransportException {
      super(address, 0);
    }

    @Override
    protected TSocket acceptImpl() throws TTransportException {
      TSocket ts = super.acceptImpl();
      try {
        ts.getSocket().setKeepAlive(true);
      } catch (SocketException e) {
        throw new TTransportException(e);
      }
      return ts;
    }
  }

  public TServer startMetaStore(String forceBindIP, int port,
      HiveConf conf) throws IOException {
    try {
      // Server will create new threads up to max as necessary. After an idle
      // period, it will destory threads to keep the number of threads in the
      // pool to min.
      int minWorkerThreads = conf.getIntVar(HiveConf.ConfVars.METASTORESERVERMINTHREADS);
      int maxWorkerThreads = conf.getIntVar(HiveConf.ConfVars.METASTORESERVERMAXTHREADS);
      boolean tcpKeepAlive = conf.getBoolVar(HiveConf.ConfVars.METASTORE_TCP_KEEP_ALIVE);
      boolean useFramedTransport = conf.getBoolVar(HiveConf.ConfVars.METASTORE_USE_THRIFT_FRAMED_TRANSPORT);

      // don't support SASL yet
      //boolean useSasl = conf.getBoolVar(HiveConf.ConfVars.METASTORE_USE_THRIFT_SASL);

      TServerTransport serverTransport;
      if (forceBindIP != null) {
        InetSocketAddress address = new InetSocketAddress(forceBindIP, port);
        serverTransport = tcpKeepAlive ?
            new TServerSocketKeepAlive(address) : new TServerSocket(address);

      } else {
        serverTransport = tcpKeepAlive ?
            new TServerSocketKeepAlive(port) : new TServerSocket(port);
      }

      TProcessor processor;
      TTransportFactory transFactory;

      IHMSHandler handler = HiveMetaStore.newHMSHandler("new db based metaserver", conf);

      if (conf.getBoolVar(HiveConf.ConfVars.METASTORE_EXECUTE_SET_UGI)) {
        transFactory = useFramedTransport ?
            new ChainedTTransportFactory(new TFramedTransport.Factory(),
                new TUGIContainingTransport.Factory())
            : new TUGIContainingTransport.Factory();

        processor = new TUGIBasedProcessor<IHMSHandler>(handler);
        LOG.info("Starting DB backed MetaStore Server with SetUGI enabled");
      } else {
        transFactory = useFramedTransport ?
            new TFramedTransport.Factory() : new TTransportFactory();
        processor = new TSetIpAddressProcessor<IHMSHandler>(handler);
        LOG.info("Starting DB backed MetaStore Server");
      }

      TThreadPoolServer.Args args = new TThreadPoolServer.Args(serverTransport)
          .processor(processor)
          .transportFactory(transFactory)
          .protocolFactory(new TBinaryProtocol.Factory())
          .minWorkerThreads(minWorkerThreads)
          .maxWorkerThreads(maxWorkerThreads);

      final TServer tServer = new TThreadPoolServer(args);
      executorService.submit(new Runnable() {
        @Override
        public void run() {
          tServer.serve();
        }
      });
      return tServer;
    } catch (Throwable x) {
      throw new IOException(x);
    }
  }
}