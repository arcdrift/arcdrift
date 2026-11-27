class Arc < Formula
  # Checksums and version are rendered automatically by .github/workflows/release.yml
  # and attached to each GitHub Release as `arc.rb`. Copy that rendered file into your tap.
  desc "IDE-agnostic AI Architecture Control Plane, Spec-and-Verify Engine, and MCP Server"
  homepage "https://github.com/arcdrift/arcdrift"
  version "0.1.0"
  license "Apache-2.0"

  on_macos do
    if Hardware::CPU.arm?
      url "https://github.com/arcdrift/arcdrift/releases/download/v#{version}/arc-standalone-#{version}-darwin-arm64.tar.gz"
      sha256 "REPLACE_WITH_DARWIN_ARM64_SHA256"
    else
      url "https://github.com/arcdrift/arcdrift/releases/download/v#{version}/arc-standalone-#{version}-darwin-x86_64.tar.gz"
      sha256 "REPLACE_WITH_DARWIN_X86_64_SHA256"
    end
  end

  on_linux do
    if Hardware::CPU.arm?
      url "https://github.com/arcdrift/arcdrift/releases/download/v#{version}/arc-standalone-#{version}-linux-arm64.tar.gz"
      sha256 "REPLACE_WITH_LINUX_ARM64_SHA256"
    else
      url "https://github.com/arcdrift/arcdrift/releases/download/v#{version}/arc-standalone-#{version}-linux-x86_64.tar.gz"
      sha256 "REPLACE_WITH_LINUX_X86_64_SHA256"
    end
  end

  def install
    libexec.install Dir["*"]
    bin.install_symlink libexec/"bin/arc"
  end

  test do
    assert_match version.to_s, shell_output("#{bin}/arc --version")
  end
end
