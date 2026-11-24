Arnés para probar `core` sin el plugin de Android (útil donde Maven de Google está bloqueado):

    mkdir -p /tmp/corecheck && cp tools/corecheck/*.kts /tmp/corecheck/ && cp -r gradle gradlew /tmp/corecheck/
    ln -sfn "$PWD/core/src" /tmp/corecheck/src
    (cd /tmp/corecheck && ./gradlew -q test --max-workers=1)
