package dev.apksim.serve;

import java.io.IOException;
import java.util.Base64;
import java.util.List;

/**
 * Checks `--network-host` and `--network-trust` as serve and beam launch options (issue 429, scripts/check-host-allow.sh,
 * CI): refused with no allow list, allowed only to a listed address (a name as written, a port when the entry has one),
 * a certificate only inline and only when it parses, and the counts. Exits 1 if any check fails.
 */
public final class HostAllowCheck {
  private static int failures;

  private HostAllowCheck() {}

  private static void check(boolean ok, String what) {
    System.out.println((ok ? "ok   " : "FAIL ") + what);
    if (!ok) failures++;
  }

  private static boolean accepted(String... opts) {
    try {
      Catalog.launchOptions(List.of(opts));
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  private static String why(String... opts) {
    try {
      Catalog.launchOptions(List.of(opts));
      return "";
    } catch (IOException e) {
      return e.getMessage();
    }
  }

  public static void main(String[] args) throws Exception {
    // A self-signed certificate (PEM), made for this check only.
    String pem = "-----BEGIN CERTIFICATE-----\n" +
      "MIIDBjCCAe6gAwIBAgIUIOnlBB9bAGFAb4uvnH428UkOb1cwDQYJKoZIhvcNAQEL\n" +
      "BQAwIjEgMB4GA1UEAwwXYXBrc2ltLWhvc3QtYWxsb3ctY2hlY2swIBcNMjYxMTE3\n" +
      "MDA1MDMyWhgPMjEyNjEwMjQwMDUwMzJaMCIxIDAeBgNVBAMMF2Fwa3NpbS1ob3N0\n" +
      "LWFsbG93LWNoZWNrMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAg8CP\n" +
      "FJUcVDQ6Gl2MfC12JlHtdZrtk/pq2j6SsBmaHt3WrFQkAugdc2ECiAmYhi5SdWRp\n" +
      "2kFNIlRq+FEadP3DmVQecCowDCVDnq7/mRv8DSg4LUQWQ3kTBFYabn9pFFnyfAv7\n" +
      "Fy8eZRQPvDscxO9eWTVzKnl7a4hFACj30xNiKY+fjqkWc+E2crOC44yLKM9Bo94Y\n" +
      "eG01gGRfV1IAZ1DnkKiprezaRiBjE41HPM2mgxGdQpwc1vPPQce/C+HszaXVt00a\n" +
      "KvPmRlYSgM9DMzJbxNGYNbB1ckb5qI4mP8NvjPqW2HXfp7ioR3UuYqa1Ilb1z/u+\n" +
      "gWetOIa6MGKlXAoCMQIDAQABozIwMDAdBgNVHQ4EFgQUly7efVTNv+R3ghRGk4JN\n" +
      "qBMLv40wDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEAR4c2hNVc\n" +
      "MBRxyLVQDd1Z1erY2QZNAxhdBMXSln8b77oTDuvSJGO8Vh53bYfhzQ4ZgVNlu9IG\n" +
      "ARFEkRcTyvEItbIMenENjoYQ1I+O5AtHQmNvHOrqnvQHyHfCU8UA/3cxlHhSjRao\n" +
      "Yf32zjPO4znGR2G5CAhHBQ3wajACHVlyadda2yuTK/ESN1HpiY4LVRXUVKJ/uLpm\n" +
      "QrdmL7QUXBr0p+34usuHs3srDjFjnesvYeLl/0GsMtCT/G+mjK+DnsYKI//ia43q\n" +
      "yXgzNaUcX5aSmFl05Ntvi+zyodZRrhSXlOnch7LxaxwXtPHHwqdtZkvrEwdAH1R1\n" +
      "1rop8S0u66HJRw==\n" +
      "-----END CERTIFICATE-----\n";
    String inline = "inline:" + Base64.getEncoder().encodeToString(pem.getBytes());

    Catalog.hostAllowance = HostAllow::new;
    check(!accepted("--network-host", "api.example.com=10.0.0.5:8443"), "no allow list: refused");
    check(why("--network-host", "api.example.com=10.0.0.5:8443").contains("--network-host-allow"), "the refusal names the server's flag");

    HostAllow a = new HostAllow();
    a.add("10.0.0.5:8443");
    a.add("Preview.Internal");
    a.add("[::1]:9000");
    Catalog.hostAllowance = () -> a;
    check(accepted("--network-host", "api.example.com=10.0.0.5:8443"), "listed address and port");
    check(!accepted("--network-host", "api.example.com=10.0.0.5:8444"), "listed address, another port");
    check(!accepted("--network-host", "api.example.com=10.0.0.5"), "an entry with a port does not cover keeping the app's port");
    check(!accepted("--network-host", "api.example.com=10.0.0.6:8443"), "another address");
    check(accepted("--network-host", "api.example.com=preview.internal"), "an entry without a port covers every port and none (case folded)");
    check(accepted("--network-host", "api.example.com=PREVIEW.internal:443"), "any port on a portless entry");
    check(!accepted("--network-host", "api.example.com=preview.internal.evil.example"), "a name is matched whole");
    check(!accepted("--network-host", "api.example.com=evil-preview.internal"), "not as a suffix");
    check(accepted("--network-host", "api.example.com=[::1]:9000"), "IPv6 in brackets");
    check(!accepted("--network-host", "api.example.com=::1"), "a bare IPv6 address is not a target");
    check(!accepted("--network-host", "api.example.com=127.0.0.1:1"), "loopback is not allowed unless listed");
    check(!accepted("--network-host", "*.example.com=10.0.0.5:8443"), "a pattern is not a host");
    check(!accepted("--network-host", "=10.0.0.5:8443"), "no host");
    check(!accepted("--network-host", "api.example.com"), "no target");
    check(!accepted("--network-host", "api.example.com=10.0.0.5:0"), "port 0 is not a port");
    check(!accepted("--network-host", "a=b=10.0.0.5:8443"), "one = only");
    check(!accepted("--network-host", "x,y=10.0.0.5:8443"), "no comma (the launcher's separator)");

    StringBuilder many = new StringBuilder();
    List<String> nine = new java.util.ArrayList<>();
    for (int i = 0; i < 9; i++) {
      nine.add("--network-host");
      nine.add("h" + i + ".example.com=10.0.0.5:8443");
    }
    try {
      Catalog.launchOptions(nine);
      check(false, "nine mappings are too many");
    } catch (IOException e) {
      check(e.getMessage().contains("at most"), "nine mappings are too many");
    }
    check(accepted(nine.subList(0, 16).toArray(String[]::new)), "eight are not");

    check(accepted("--network-trust", inline), "an inline certificate");
    check(!accepted("--network-trust", "/etc/hosts"), "a file path is refused");
    check(!accepted("--network-trust", "inline:" + Base64.getEncoder().encodeToString("not a certificate".getBytes())), "bytes that are not a certificate");
    check(!accepted("--network-trust", "inline:"), "empty");
    check(!accepted("--network-trust", "inline:" + "A".repeat(Catalog.NETWORK_TRUST_MAX)), "too long");
    check(!accepted("--network-trust", inline, "--network-trust", inline), "one certificate value");

    // The agent's list: what `apksim beam config allow-host` writes is read back, entries normalised.
    HostAllow fromFile = new HostAllow(List.of("10.0.0.5:8443", "Preview.Internal", "bad entry", "10.0.0.5:99999"));
    check(fromFile.list().equals(List.of("10.0.0.5:8443", "preview.internal")), "an unreadable entry is dropped: " + fromFile.list());
    check(HostAllow.parse("[::1]:9000").toString().equals("[::1]:9000"), "entries print as given");
    Catalog.hostAllowance = HostAllow::new;
    if (failures > 0) {
      System.out.println(failures + " checks failed");
      System.exit(1);
    }
    System.out.println("all checks passed");
  }
}
