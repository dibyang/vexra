package net.xdob.vexra.adb;

import java.sql.*;
import java.util.Random;

public class JdbcTest {
  public static void main(String[] args) throws Exception {

    Class.forName("org.adb.Driver");
    String user = "remote";
    String password = "hhrhl2016";
    String url = "jdbc:adb:ldb:/test/db/b_db2;AUTO_SERVER=true";
    Connection conn = DriverManager.getConnection(url, user, password);

    Statement stat = conn.createStatement();
    conn.setAutoCommit(true);
    // 2️⃣ 创建表，使用 LMDB 自定义引擎
    //stat.execute("RUNSCRIPT FROM 'e:\\test\\tableCreates.sql';");
    //createConfigTable(stat);
    //createDistrictTable(stat);
    //createDistrictPk(stat);
    //createHistoryTable( stat);
    //createHistoryRefDistrict( stat);
    //stat.execute("drop table bmsql_district;");
    //stat.execute("drop table bmsql_config;");
    //stat.execute("truncate table bmsql_district;");
    showTables(stat);
    //showUser(stat);

    //insertDistrictData(conn);
    //createDistrictPk(stat);
//    for (int i = 0; i < 5; i++) {
//      insertCfg(conn, 5*i, 5);
//      queryCfg(stat);
//      queryCfgCount(stat);
//    }
    queryCfgCount(stat);
//    updateCfg(conn);

    queryCfg(conn, "cfg2");

    //queryItem(conn, 999999);
    int w_id=1;
    int d_id=2;
    //updateDistrict(conn, w_id, d_id);

    //queryDistrict(conn, w_id, d_id);
    System.out.println("=================================");
    //queryDistrict(conn);

    //conn.commit();
    //query(conn, w_id, d_id);
    System.out.println("test is end");

//    ResultSet rs1 = stat.executeQuery("SELECT * FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_NAME = 'BMSQL_CONFIG';");
//    ResultSetMetaData metaData = rs1.getMetaData();
//    while (rs1.next()) {
//      for (int i = 1; i <= metaData.getColumnCount(); i++) {
//        System.out.println("\t"+rs1.getObject(i));
//      }
//      System.out.println("-------------------------------");
//    }


    // 7️⃣ 关闭连接
    stat.close();
    conn.close();
  }

  private static void queryCfg(Statement stat) throws SQLException {
    ResultSet rs = stat.executeQuery("select * from bmsql_config;");
    while (rs.next()) {
      System.out.println("CFG_NAME\t|\tCFG_VALUE");
      System.out.println(rs.getString("CFG_NAME")+"\t|\t"+rs.getString("CFG_VALUE"));
    }
    System.out.println("===============================================");
  }

  private static void queryCfgCount(Statement stat) throws SQLException {
    ResultSet rs = stat.executeQuery("select count(*) as c from bmsql_config;");
    while (rs.next()) {
      //System.out.println("c=" + rs.getInt("c"));
      System.out.println(" row=" + rs.getInt("c"));
    }
    System.out.println("===============================================");
  }

  private static void showUser(Statement stat) throws SQLException {
    ResultSet rs = stat.executeQuery("select * from UTILITY_USER;");
    while (rs.next()) {
      System.out.println(" username=" + rs.getString("username") +" is find");
    }
  }

  private static void showTables(Statement stat) throws SQLException {
    ResultSet rs = stat.executeQuery("show tables;");
    while (rs.next()) {
      System.out.println("table=" + rs.getString(1));
    }
  }

  private static void queryItem(Connection conn, int i_id) throws SQLException {
    PreparedStatement ps = conn.prepareStatement("select * from bmsql_item where i_id = ?;"
        //+ "ORDER BY cfg_name"
    );
    ps.setInt(1, i_id);
    ResultSet rs = ps.executeQuery();
    while (rs.next()) {
      System.out.println("i_id=" + rs.getInt("i_id") + " i_name=" + rs.getString("i_name") +" is find");
    }

  }

  private static void updateCfg(Connection conn) throws SQLException {
    PreparedStatement ps = conn.prepareStatement("UPDATE bmsql_config set cfg_value =? "
        + "WHERE  cfg_name= ?;");
    for (int i = 0; i < 1; i++) {
      ps.setString(1, String.valueOf( 100*i));
      ps.setString(2, "cfg"+i);

      ps.addBatch();
    }
    ps.executeBatch();
    ps.clearBatch();
    conn.commit();

  }

  private static void insertCfg(Connection conn, int start , int count) throws SQLException {
    PreparedStatement ps = conn.prepareStatement("INSERT INTO bmsql_config (cfg_name, cfg_value) "
        + "VALUES (?, ?);");
    for (int i = start; i < start+count; i++) {
      ps.setString(1, "cfg"+i);
      ps.setString(2, String.valueOf( i));
      ps.addBatch();
    }
    ps.executeBatch();
    ps.clearBatch();
    conn.commit();
    System.out.println("insert start = " + start +" count="+ count);
  }

  private static void createConfigTable(Statement stat) throws SQLException {
    stat.execute("create table bmsql_config (\n" +
        "  cfg_name    varchar(30) primary key,\n" +
        "  cfg_value   varchar(50)\n" +
        ");");
  }

  private static void queryCfg(Connection conn, String cfg_name) throws SQLException {
    //System.out.println("query d_id=" + d_id + ", d_w_id=" + d_w_id + "");
    PreparedStatement ps = conn.prepareStatement("SELECT * " +
        "FROM bmsql_config "
        + "WHERE cfg_name = ? "
        //+ "ORDER BY cfg_name"
    );
    ps.setString(1, cfg_name);
    ResultSet rs = ps.executeQuery();
    while (rs.next()) {
      System.out.println("cfg_name=" + rs.getString("cfg_name") + " cfg_value=" + rs.getInt("cfg_value") +" is find");
    }

  }

  private static void queryDistrict(Connection conn) throws SQLException {
    //System.out.println("query d_id=" + d_id + ", d_w_id=" + d_w_id + "");
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT d_w_id, d_id, d_ytd, d_tax, d_next_o_id, d_name, d_street_1, d_street_2, d_city, d_state, d_zip\n" +
        "FROM bmsql_district\n"
        + "ORDER BY d_w_id, d_id"
    );
    while (rs.next()) {
      System.out.println("d_w_id=" + rs.getInt("d_w_id") + " d_id=" + rs.getInt("d_id") + " d_ytd=" + rs.getInt("d_ytd") +" is find");
    }

  }

  private static void queryDistrict(Connection conn, int d_w_id, int d_id) throws SQLException {
    //System.out.println("query d_id=" + d_id + ", d_w_id=" + d_w_id + "");
    PreparedStatement ps2 = conn.prepareStatement("SELECT d_w_id, d_id, d_ytd, d_tax, d_next_o_id, d_name, d_street_1, d_street_2, d_city, d_state, d_zip\n" +
            "FROM bmsql_district\n"
            +" WHERE d_w_id = ? AND d_id = ? "
        //+" ORDER BY d_w_id, d_id;"
    );
    ps2.setInt(1, d_w_id);
    ps2.setInt(2, d_id);
    //ps2.setString(3, "BARBARBAR");
    ResultSet rs = ps2.executeQuery();
    if (rs.next()) {
      System.out.println("0 d_w_id=" + rs.getInt("d_w_id") + " d_id=" + rs.getInt("d_id") + " d_next_o_id=" + rs.getInt("d_next_o_id") +" is find");
    }
//    else{
//      System.out.println("0 c_d_id=" + c_d_id + ", c_w_id=" + c_w_id + " not found");
//    }
  }

  private static void updateDistrict(Connection conn, int d_w_id, int d_id) throws SQLException {

    PreparedStatement ps3 = conn.prepareStatement(
        "UPDATE bmsql_district "
            + "    SET d_next_o_id = d_next_o_id + 1 "
            + "    WHERE d_w_id = ? AND d_id = ?");

    ps3.setInt(1, d_w_id);
    ps3.setInt(2, d_id);
    ps3.execute();
    conn.commit();
    System.out.println("update d_id=" + d_id + ", d_w_id=" + d_w_id + " set  d_next_o_id = d_next_o_id + 1");
  }

  private static void insertDistrictData(Connection conn) throws SQLException {
    PreparedStatement stmtItem = conn.prepareStatement(
        "INSERT INTO bmsql_district (" + "  d_id, d_w_id, d_name, d_street_1, d_street_2, "
            + "  d_city, d_state, d_zip, d_tax, d_ytd, d_next_o_id) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
    int count = 10;
    int wCount = 2;
    int dCount = count/wCount;
    //conn.setAutoCommit(false);
    int rows = 0;
    // 3️⃣ 插入数据
    Random rnd = new Random();
    for (int w_id = 1; w_id <= wCount; w_id++) {
      for (int d_id = 1; d_id <= dCount; d_id++) {
        stmtItem.setInt(1, d_id);
        stmtItem.setInt(2, w_id);
        stmtItem.setString(3, "name_" + d_id);
        stmtItem.setString(4, "s1_" + d_id);
        stmtItem.setString(5, "s2_" + d_id);
        stmtItem.setString(6, "" + rnd.nextInt(100));
        stmtItem.setString(7, "s" + rnd.nextInt(100) % 8);
        stmtItem.setString(8, "" + rnd.nextInt(10));
        stmtItem.setDouble(9, ((double) rnd.nextInt(2000)) / 10000.0);
        stmtItem.setDouble(10, 30000.0);
        stmtItem.setInt(11, 3001);
        stmtItem.addBatch();
      }
      stmtItem.executeBatch();
      rows += dCount;
      stmtItem.clearBatch();
    }
    conn.commit();
    stmtItem.close();
  }

  private static void createDistrictTable(Statement stat) throws SQLException {
    stat.execute("create table bmsql_district (\n" +
        "  d_w_id       integer       not null,\n" +
        "  d_id         integer       not null,\n" +
        "  d_ytd        decimal(12,2),\n" +
        "  d_tax        decimal(4,4),\n" +
        "  d_next_o_id  integer,\n" +
        "  d_name       varchar(10),\n" +
        "  d_street_1   varchar(20),\n" +
        "  d_street_2   varchar(20),\n" +
        "  d_city       varchar(20),\n" +
        "  d_state      char(2),\n" +
        "  d_zip        char(9)\n" +
        ");" );

  }

  private static void createHistoryTable(Statement stat) throws SQLException {
    stat.execute("create table bmsql_history (\n" +
        "  h_c_id   integer,\n" +
        "  h_d_id   integer,\n" +
        "  h_w_id   integer,\n" +
        "  h_date   timestamp,\n" +
        "  h_amount decimal(6,2),\n" +
        "  h_data   varchar(24)\n" +
        ");" );

  }

  private static void createDistrictPk(Statement stat) throws SQLException {
    stat.execute("alter table bmsql_district add constraint bmsql_district_pkey\n" +
        "  primary key (d_w_id, d_id);");
  }
  private static void createHistoryRefDistrict(Statement stat) throws SQLException {
    stat.execute("alter table bmsql_history add constraint h_district_fkey\n" +
        "    foreign key (h_w_id, h_d_id)\n" +
        "    references bmsql_district (d_w_id, d_id);");
  }
}
