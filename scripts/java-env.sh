# Cargar con source. JAVA_HOME permite seleccionar explícitamente el JDK.
if [[ -n "${JAVA_HOME:-}" ]]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi
if ! command -v javac >/dev/null 2>&1; then
    echo 'Falta javac. Instale un JDK 21 y configure JAVA_HOME (consulte README.md).' >&2
    exit 1
fi
