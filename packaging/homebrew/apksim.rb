# Homebrew cask for the aglose/homebrew-apksim tap: brew tap aglose/apksim, then brew install apksim
# .github/workflows/release.yml fills in the version and checksum of each published release and pushes the result to
# the tap as Casks/apksim.rb; this copy is the template.
# A cask rather than a formula: the release is prebuilt and self-contained (its own JDK 24 runtime, libraries and
# native code), and Homebrew installs a formula without a bottle only with up-to-date Command Line Tools.
cask "apksim" do
  version "@VERSION@"
  sha256 "@SHA256@"

  url "https://github.com/aglose/apksim/releases/download/v#{version}/apksim-#{version}-macos-arm64.tar.gz"
  name "apksim"
  desc "Run unmodified Android APKs on an Apple Silicon Mac as one process, no VM"
  homepage "https://github.com/aglose/apksim"

  depends_on arch: :arm64

  # The launcher follows this link to its folder, and keeps what it writes in ~/.apksim.
  binary "apksim-#{version}-macos-arm64/apksim"

  # The release is not notarized: Gatekeeper would block its JDK and native libraries while they carry the download's
  # quarantine flag. (Its standbys, background JVMs that make launches warm, quit by themselves once it is removed.)
  postflight_steps do
    run "/usr/bin/xattr", args: ["-dr", "com.apple.quarantine", "."], chdir: ".", writable_paths: ["."]
  end

  zap trash: "~/.apksim"

  caveats <<~EOS
    The first launch builds a startup cache in ~/.apksim in the background (1-2 minutes, once per version);
    launches are faster after that. To build it now instead:
      apksim --train
  EOS
end
