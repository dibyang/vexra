package net.xdob.vexra.adb.db;

enum UniqueCheckResult {
  DUPLICATE,
  CONCURRENT_CONFLICT,
  IGNORE
}
