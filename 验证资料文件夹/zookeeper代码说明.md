## zookeeper代码说明

### 1.代码结构说明

提交的代码总共包含三个module。分别为**client，server-1，server-2** 

其中服务器端代码server-1和server-2的不同点**仅仅为netty使用的端口不同**。

- server-1使用9999端口
- server-2使用1111端口



### 2.分别针对三道作业分析代码

#### 2.1 作业一 在基于Netty的自定义RPC的案例基础上，进行改造。基于Zookeeper实现简易版服务的注册与发现机制

1. 启动2个服务端，可以将IP及端口信息自动注册到Zookeeper  **服务器端NettyRpcStater.registerInZooKeeper()**

```java
public void registerInZooKeeper() throws Exception {
  			//获取zk链接
        zkClient = CuratorFrameworkFactory.builder()
                .connectString("127.0.0.1:2181")
                .sessionTimeoutMs(5000)
                .connectionTimeoutMs(3000)
                .retryPolicy(new ExponentialBackoffRetry(1000, 3))
                .build();
        zkClient.start();
        System.out.println("zookeeper服务连接成功");
        //创建临时路径。/server/host:port;
        zkClient.create().creatingParentContainersIfNeeded()
          .withMode(CreateMode.EPHEMERAL).forPath("/server/" + getHostName(), "0".getBytes());
        List<String> serverList = zkClient.getChildren().forPath("/server");
        System.out.println("当前在线服务器：" + serverList);
    }
```



2. 客户端启动时，从Zookeeper中获取所有服务提供端节点信息，客户端与每一个服务端都建立连接

   某个服务端下线后，Zookeeper注册列表会自动剔除下线的服务端节点，客户端与下线的服务端断开连接

   服务端重新上线，客户端能感知到，并且与重新上线的服务端重新建立连接

   **client模块中RPCConsumer.connectZK()**

```java
private static void connectZK() throws Exception {
        client = CuratorFrameworkFactory.builder()
                .connectString("127.0.0.1:2181")
                .sessionTimeoutMs(5000)
                .connectionTimeoutMs(3000)
                .retryPolicy(new ExponentialBackoffRetry(1000, 3))
                .build();
        client.start();
  			//使用PathChildrenCache反复wathch节点zk变化
        PathChildrenCache pathChildrenCache = new PathChildrenCache(client, "/server", true);
        //调用start方法开始监听 ，设置启动模式为同步加载节点数据
        pathChildrenCache.start(PathChildrenCache.StartMode.BUILD_INITIAL_CACHE);
        //添加监听器
        pathChildrenCache.getListenable().addListener((client, event) -> {
          	 //当节点发生remove事件说明客户端下线，从本地连接中移除
             //当节点发生add事件说明客户端上线，重新创建链接
            if (event.getType() == PathChildrenCacheEvent.Type.CHILD_REMOVED) {
              	//获取发生变化的节点名称
                String serverAddress = event.getData().getPath().substring(event.getData().getPath().lastIndexOf("/") + 1);
                futureMap.remove(serverAddress);
            } else if (event.getType() == PathChildrenCacheEvent.Type.CHILD_ADDED) {
               //获取发生变化的节点名称
                String serverAddress = event.getData().getPath().substring(event.getData().getPath().lastIndexOf("/") + 1);
                addNewChannelFuture(serverAddress);
            }
        });
    }
```



#### 2.2 作业二 基于作业一的基础上，实现基于Zookeeper的简易版负载均衡策略

1. Zookeeper记录每个服务端的最后一次响应时间，有效时间为5秒，5s内如果该服务端没有新的请求，响应时间清零或失效

**服务器端NettyRpcStater.startServer()片段**

```java
        //新建一个线程处理响应的有效时间，如果超过5s则响应时间清0。
        //防止某一次服务器响应慢导致一直不请求该服务器
        new Thread(() -> {
            while (true) {
              	//获取节点状态信息，主要获取节点上一次修改时间，如果上一次修改时间超过5s则保存的响应时间清0
                Stat stat = new Stat();
                try {
                  	//获取stat值
                    zkClient.getData().storingStatIn(stat).forPath("/server/" + getHostName());
                  	//判断上一次修改时间时候满足清0条件
                    if (System.currentTimeMillis() - stat.getMtime() > 5 * 1000) {
                        zkClient.setData().forPath("/server/" + getHostName(), "0".getBytes());
                        System.out.println("超时重置服务器响应时间" + getHostName());
                    }
                    Thread.sleep(1 * 1000);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
```

2. 当客户端发起调用，每次都选择最后一次响应时间短的服务端进行服务调用，如果时间一致，随机选取一个服务端进行调用，从而实现负载均衡

   **client模块中ConsumerBoot.chooseServer()**

```java
private static ChannelFuture chooseServer() throws Exception {
        String targetKey = null;
        Long shortResponseTime = null;
        for (String server : RPCConsumer.futureMap.keySet()) {
          	//从zk中分别获取服务器保存的响应时间 并比较取得响应时间最短的服务器。
          	//使用选中的服务器发送请求
            long serverResponseTime = new Long(new String(RPCConsumer.client.getData().forPath("/server/" + server)));
            if (shortResponseTime == null || serverResponseTime < shortResponseTime) {
                shortResponseTime = serverResponseTime;
                targetKey = server;
            }
        }
        return targetKey == null ? null : RPCConsumer.futureMap.get(targetKey);
    }

```



#### 2.3 作业三 基于Zookeeper实现简易版配置中心

1. 创建一个Web项目，将数据库连接信息交给Zookeeper配置中心管理，即：当项目Web项目启动时，从Zookeeper进行MySQL配置参数的拉取

   当Zookeeper配置信息变化后Web项目自动感知，正确释放之前连接池，创建新的连接池

**client模块中DbPoolConnectUtil.initConnectWithZK()** 

```java
public static void initConnectWithZK(CuratorFramework zkClient) throws Exception {
  			//使用NodeCache 监听配置节点下的数据变化
        NodeCache nodeCache = new NodeCache(zkClient, "/db", false);
        nodeCache.start();
        nodeCache.getListenable().addListener(() -> {
          	//如果发生数据变化则重新创建数据库链接
            properties.load(new ByteArrayInputStream(nodeCache.getCurrentData().getData()));
            connectToDb();
        });
  			//如果节点不存在 创建 并初始化配置
        Stat stat = zkClient.checkExists().forPath("/db");
        //设置默认值 自动出发watch连接数据库
        if (stat == null) {
            zkClient.create().withMode(CreateMode.PERSISTENT).forPath("/db", "url=jdbc:mysql://localhost:3306/test\n driver_class=com.mysql.jdbc.Driver\n user_name=root\n password=root".getBytes());
        } else {
            //否则获取后连接
            properties.load(new ByteArrayInputStream(zkClient.getData().forPath("/db")));
            connectToDb();
        }
    }
```

2. 要求项目通过数据库连接池访问MySQL（连接池可以自由选择熟悉的）

**client模块中DbPoolConnectUtil.connectToDb()**  使用druid连接池

```java
 private static void connectToDb() {
        dataSource = new DruidDataSource();
        dataSource.setUrl(properties.getProperty("url"));
        dataSource.setDriverClassName(properties.getProperty("driver_class"));
        dataSource.setUsername(properties.getProperty("user_name"));
        dataSource.setPassword(properties.getProperty("password"));
    }
```



3. 为验证切换效果。执行查询数据库名称

```java
					 connection = dataSource.getConnection();
            statement = connection.createStatement();
            //查询当前连接的数据库名称
            String sql = "select database() as db_name;";
            rs = statement.executeQuery(sql);
            while (rs.next()) {
                String dbName = rs.getString("db_name");
              	//在控制台显示当前连接的数据库
                System.out.println("当前连接的数据库为>>>>>>> " + dbName);
            }
```

