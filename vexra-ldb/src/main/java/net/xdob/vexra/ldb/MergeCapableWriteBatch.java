package net.xdob.vexra.ldb;

public interface MergeCapableWriteBatch extends LdbWriteBatch {
  LdbWriteBatch merge(byte[] key, byte[] operand);
}
