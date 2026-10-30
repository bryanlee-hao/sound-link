package app.soundlink.android;

/** One bounded mute owner, with at most one explicit wait-to-connect transfer. */
final class MuteLease {
  private String token = "";
  private long deadline;
  private boolean transferred;

  String token() { return token; }
  boolean active() { return !token.isEmpty(); }
  long deadline() { return deadline; }

  void begin(String id, long now, long budget) {
    if (active() || id.isEmpty() || budget <= 0 || budget > 5000)
      throw new IllegalStateException("Invalid mute lease");
    token = id;
    deadline = now + budget;
    transferred = false;
  }

  boolean canTransfer(String from, long now) {
    return active() && token.equals(from) && !transferred && now < deadline;
  }

  boolean transfer(String from, String to, long now, long budget) {
    if (!canTransfer(from, now) || to.isEmpty() || to.equals(from)
        || budget <= 0 || budget > 5000) return false;
    token = to;
    deadline = now + budget;
    transferred = true;
    return true;
  }

  void clear() {
    token = "";
    deadline = 0;
    transferred = false;
  }
}
