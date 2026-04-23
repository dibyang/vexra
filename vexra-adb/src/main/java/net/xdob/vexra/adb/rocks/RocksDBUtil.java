package net.xdob.vexra.adb.rocks;

import org.rocksdb.RocksDBException;
import org.rocksdb.Status;

import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import java.sql.SQLTransientException;

public class RocksDBUtil {
  public static SQLException convert(RocksDBException e) {
    Status status = e.getStatus();

    if (status == null) {
      return new SQLException(e.getMessage(), "HY000", e);
    }

    switch (status.getCode()) {

      case NotFound:
        return new SQLException(e.getMessage(), "02000", e);

      case Corruption:
        return new SQLException(e.getMessage(), "58030", e);

      case IOError:
        return new SQLException(e.getMessage(), "58030", e);

      case InvalidArgument:
        return new SQLException(e.getMessage(), "22000", e);

      case TimedOut:
        return new SQLTransientException(e.getMessage(), "HYT00", e);

      case Busy:
        return new SQLTransientException(e.getMessage(), "HY000", e);

      case Aborted:
        return new SQLTransactionRollbackException(e.getMessage(), "40001", e);

      default:
        return new SQLException(e.getMessage(), "HY000", e);
    }
  }
}
