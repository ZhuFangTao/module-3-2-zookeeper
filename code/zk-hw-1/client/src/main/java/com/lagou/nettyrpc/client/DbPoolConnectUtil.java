package com.lagou.nettyrpc.client;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.DruidPooledConnection;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.NodeCache;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.data.Stat;

import java.io.ByteArrayInputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * \* @Author: ZhuFangTao
 * \* @Date: 2020/6/30 上午10:45
 * \
 */

public class DbPoolConnectUtil {

    private static DruidDataSource dataSource;

    private static Properties properties = new Properties();

    private static void connectToDb() {
        dataSource = new DruidDataSource();
        dataSource.setUrl(properties.getProperty("url"));
        dataSource.setDriverClassName(properties.getProperty("driver_class"));
        dataSource.setUsername(properties.getProperty("user_name"));
        dataSource.setPassword(properties.getProperty("password"));
        dataSource.setMaxWait(3000);
    }

    public static void initConnectWithZK(CuratorFramework zkClient) throws Exception {
        NodeCache nodeCache = new NodeCache(zkClient, "/db", false);
        nodeCache.start();
        nodeCache.getListenable().addListener(() -> {
            properties.load(new ByteArrayInputStream(nodeCache.getCurrentData().getData()));
            connectToDb();
        });
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

    public static void queryForData() throws SQLException {
        if(dataSource == null){
            return;
        }
        DruidPooledConnection connection = null;
        Statement statement = null;
        ResultSet rs = null;
        try {
            connection = dataSource.getConnection();
            statement = connection.createStatement();
            //查询当前连接的数据库名称
            String sql = "select database() as db_name;";
            rs = statement.executeQuery(sql);
            while (rs.next()) {
                String dbName = rs.getString("db_name");
                System.out.println("当前连接的数据库为>>>>>>> " + dbName);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (rs != null) {
                    rs.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            try {
                if (statement != null) {
                    statement.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            try {
                if (connection != null) {
                    connection.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}