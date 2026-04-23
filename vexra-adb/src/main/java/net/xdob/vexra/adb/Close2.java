package net.xdob.vexra.adb;


public interface Close2 {
  static void close(AutoCloseable  o)  {
    if(o !=null){
      try {
        o.close();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }
}
