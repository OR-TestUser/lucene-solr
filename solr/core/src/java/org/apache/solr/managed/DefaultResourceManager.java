package org.apache.solr.managed;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.solr.common.util.ExecutorUtil;
import org.apache.solr.common.util.IOUtils;
import org.apache.solr.common.util.TimeSource;
import org.apache.solr.core.PluginInfo;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.util.DefaultSolrThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 */
public class DefaultResourceManager extends ResourceManager {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static final String SCHEDULE_DELAY_SECONDS_PARAM = "scheduleDelaySeconds";
  public static final String MAX_NUM_POOLS_PARAM = "maxNumPools";

  public static final int DEFAULT_MAX_POOLS = 100;
  public static final int DEFAULT_SCHEDULE_DELAY_SECONDS = 60;

  protected int maxNumPools = DEFAULT_MAX_POOLS;

  protected Map<String, ResourceManagerPool> resourcePools = new ConcurrentHashMap<>();


  private TimeSource timeSource;

  /**
   * Thread pool for scheduling the pool runs.
   */
  private ScheduledThreadPoolExecutor scheduledThreadPoolExecutor;

  protected boolean isClosed = false;
  protected boolean enabled = true;

  protected ResourceManagerPluginFactory resourceManagerPluginFactory;
  protected SolrResourceLoader loader;

  public DefaultResourceManager(SolrResourceLoader loader, TimeSource timeSource) {
    this.loader = loader;
    this.timeSource = timeSource;
  }

  protected void doInit() throws Exception {
    scheduledThreadPoolExecutor = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(maxNumPools,
        new DefaultSolrThreadFactory(getClass().getSimpleName()));
    scheduledThreadPoolExecutor.setRemoveOnCancelPolicy(true);
    scheduledThreadPoolExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    // TODO: make configurable
    resourceManagerPluginFactory = new DefaultResourceManagerPluginFactory(loader);
  }

  public void setMaxNumPools(Integer maxNumPools) {
    if (maxNumPools != null) {
      this.maxNumPools = maxNumPools;
    } else {
      this.maxNumPools = DEFAULT_MAX_POOLS;
    }
  }

  @Override
  public PluginInfo getPluginInfo() {
    return pluginInfo;
  }

  @Override
  public void createPool(String name, String type, Map<String, Float> limits, Map<String, Object> params) throws Exception {
    ensureNotClosed();
    if (resourcePools.containsKey(name)) {
      throw new IllegalArgumentException("Pool '" + name + "' already exists.");
    }
    if (resourcePools.size() >= maxNumPools) {
      throw new IllegalArgumentException("Maximum number of pools (" + maxNumPools + ") reached.");
    }
    ResourceManagerPool newPool = new ResourceManagerPool(name, type, resourceManagerPluginFactory, limits, params);
    newPool.scheduleDelaySeconds = Integer.parseInt(String.valueOf(params.getOrDefault(SCHEDULE_DELAY_SECONDS_PARAM, DEFAULT_SCHEDULE_DELAY_SECONDS)));
    resourcePools.putIfAbsent(name, newPool);
    newPool.scheduledFuture = scheduledThreadPoolExecutor.scheduleWithFixedDelay(newPool, 0,
        timeSource.convertDelay(TimeUnit.SECONDS, newPool.scheduleDelaySeconds, TimeUnit.MILLISECONDS),
        TimeUnit.MILLISECONDS);
  }

  @Override
  public void modifyPoolLimits(String name, Map<String, Float> limits) throws Exception {
    ensureNotClosed();
    ResourceManagerPool pool = resourcePools.get(name);
    if (pool == null) {
      throw new IllegalArgumentException("Pool '" + name + "' doesn't exist.");
    }
    pool.setLimits(limits);
  }

  @Override
  public void removePool(String name) throws Exception {
    ensureNotClosed();
    ResourceManagerPool pool = resourcePools.remove(name);
    if (pool == null) {
      throw new IllegalArgumentException("Pool '" + name + "' doesn't exist.");
    }
    IOUtils.closeQuietly(pool);
  }

  @Override
  public void addResource(String name, ManagedResource managedResource) {
    ensureNotClosed();
    ResourceManagerPool pool = resourcePools.get(name);
    if (pool == null) {
      throw new IllegalArgumentException("Pool '" + name + "' doesn't exist.");
    }
    pool.addResource(managedResource);
  }

  @Override
  public void close() throws IOException {
    synchronized (this) {
      isClosed = true;
      log.debug("Closing all pools.");
      for (ResourceManagerPool pool : resourcePools.values()) {
        IOUtils.closeQuietly(pool);
      }
      resourcePools.clear();
    }
    log.debug("Shutting down scheduled thread pool executor now");
    scheduledThreadPoolExecutor.shutdownNow();
    log.debug("Awaiting termination of scheduled thread pool executor");
    ExecutorUtil.awaitTermination(scheduledThreadPoolExecutor);
    log.debug("Closed.");
  }

}
