// Lightweight ANSI logging wrappers. Mirrors LMNTL-AI's spartan/log.groovy contract.
//
// All methods are no-arg-safe with null guards; ansiColor is a Jenkins step that
// only works inside a node{} context, so the wrappers fall back to a plain echo
// when ansiColor is unavailable (e.g. JenkinsPipelineUnit).

void info(String message) {
  echo message ?: ''
}

void debug(String message) {
  _ansi('36', message)  // cyan
}

void warn(String message) {
  _ansi('33', message)  // yellow
}

void error(String message) {
  _ansi('31', message)  // red
}

private void _ansi(String colorCode, String message) {
  def safe = message ?: ''
  try {
    ansiColor('xterm') {
      echo "\033[${colorCode}m${safe}\033[0m"
    }
  } catch (NoSuchMethodError | MissingMethodException ignored) {
    echo safe
  }
}
