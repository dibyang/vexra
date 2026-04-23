package net.xdob.vexra.ldb;


public interface LdbColumnFamily {
  int getId();
  String getName();
  LdbColumnFamily DEFAULT = new LdbColumnFamily() {
    @Override
    public int getId() {
      return 1;
    }

    @Override
    public String getName() {
      return "default";
    }
  };
}
