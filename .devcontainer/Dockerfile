FROM mcr.microsoft.com/devcontainers/java:1-17-bookworm

# sbt launcher via SDKMAN (preinstalled in this base image). Only the launcher is pinned
# here; project/build.properties makes it fetch sbt 1.12.14 on first run.
RUN su vscode -c 'bash -lc "source /usr/local/sdkman/bin/sdkman-init.sh && sdk install sbt 1.12.4 && sdk flush"' \
 && mkdir -p /home/vscode/.sbt /home/vscode/.cache/coursier /home/vscode/.ivy2 \
 && chown -R vscode:vscode /home/vscode/.sbt /home/vscode/.cache /home/vscode/.ivy2

# Login shells rebuild PATH, so expose sbt via a symlink rather than ENV PATH.
RUN ln -s /usr/local/sdkman/candidates/sbt/current/bin/sbt /usr/local/bin/sbt
