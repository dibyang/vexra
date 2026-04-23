package net.xdob.vexra.adb;

import java.sql.*;

public class OAuthTokenTest {

  public static void main(String[] args) throws Exception {

    // 👉 换成你的 JDBC URL
    String url = "jdbc:adb:ldb:/test/db/b_db2"; // H2
    // String url = "jdbc:adb:tcp://127.0.0.1:9092/xxx"; // 你的ADB

    try (Connection conn = DriverManager.getConnection(url, "remote", "hhrhl2016")) {

      Statement stmt = conn.createStatement();

      query(stmt);
      query(conn,"a68188498ec469b381952faddff64c74");

    }
  }

  private static void query(Connection conn, String authentication_id) throws SQLException {
    System.out.println("find by authentication_id = " + authentication_id);
    PreparedStatement ps =conn.prepareStatement("select * from oauth_access_token where authentication_id = ?");
    ps.setString(1, authentication_id);
    ResultSet rs = ps.executeQuery();
    if(rs.next()){
      System.out.println("row: token_id=" + rs.getString("token_id")
          + ", authentication_id=" + rs.getString("authentication_id"));
    }else{
      System.out.println("row: not found authentication_id="+authentication_id);
    }
  }

  private static void query(Statement stmt) throws SQLException {
    System.out.println("find all");
    ResultSet rs = stmt.executeQuery("select * from oauth_access_token");
    while(rs.next()){
      System.out.println("row: token_id=" + rs.getString("token_id")
          + ", authentication_id=" + rs.getString("authentication_id"));
    }
  }

  private static void insert(Connection conn, String tokenId, String authId) throws Exception {
    try (PreparedStatement ps = conn.prepareStatement(
        "INSERT INTO oauth_access_token " +
            "(token_id, token, authentication_id, user_name, client_id, authentication, refresh_token) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)"
    )) {
      ps.setString(1, tokenId);
      ps.setBytes(2, new byte[]{1});
      ps.setString(3, authId);
      ps.setString(4, "admin");
      ps.setString(5, "client");
      ps.setBytes(6, new byte[]{2});
      ps.setString(7, "R1");

      System.out.println(">>> INSERT token=" + tokenId);
      ps.executeUpdate();
    }
  }

  private static void delete(Connection conn, String authId) throws Exception {
    try (PreparedStatement ps = conn.prepareStatement(
        "DELETE FROM oauth_access_token WHERE authentication_id = ?"
    )) {
      ps.setString(1, authId);
      System.out.println(">>> DELETE authId=" + authId);
      ps.executeUpdate();
    }
  }

  private static void print(Connection conn, String title) throws Exception {
    System.out.println("\n=== " + title + " ===");
    try (Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT token_id, authentication_id FROM oauth_access_token")) {

      while (rs.next()) {
        System.out.println("row: token=" + rs.getString(1)
            + ", auth=" + rs.getString(2));
      }
    }
  }
}
