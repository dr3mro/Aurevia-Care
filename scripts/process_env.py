"""Build child environments that cannot redirect Aurevia's Git tooling.

Repository automation must ignore inherited repository paths, injected Git
configuration, executable hooks and trace destinations. Legitimate provider and
workflow settings remain available, while a small safe configuration is added
for every child Git process.
"""
import os

# Variables that select a repository, index, object store, discovery root,
# shallow/graft/replace state, configuration source or executable/trace target.
REPOSITORY_GIT_ENV = frozenset({
    "GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_COMMON_DIR",
    "GIT_OBJECT_DIRECTORY", "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_NAMESPACE",
    "GIT_IMPLICIT_WORK_TREE", "GIT_PREFIX", "GIT_SHALLOW_FILE", "GIT_GRAFT_FILE",
    "GIT_REPLACE_REF_BASE", "GIT_QUARANTINE_PATH", "GIT_CEILING_DIRECTORIES",
    "GIT_DISCOVERY_ACROSS_FILESYSTEM", "GIT_CONFIG", "GIT_CONFIG_COUNT",
    "GIT_CONFIG_PARAMETERS", "GIT_CONFIG_SYSTEM", "GIT_CONFIG_GLOBAL",
    "GIT_EXEC_PATH", "GIT_TEMPLATE_DIR", "GIT_PROXY_COMMAND", "GIT_SSH",
    "GIT_SSH_COMMAND", "GIT_ASKPASS", "GIT_EDITOR", "GIT_SEQUENCE_EDITOR",
    "GIT_PAGER", "GIT_EXTERNAL_DIFF", "GIT_TRACE", "GIT_TRACE_PACKET",
    "GIT_TRACE_PERFORMANCE", "GIT_TRACE_SETUP", "GIT_TRACE_CURL",
    "GIT_TRACE_CURL_NO_DATA", "GIT_TRACE_SHALLOW", "GIT_TRACE2", "GIT_TRACE2_EVENT",
    "GIT_TRACE2_PERF",
})
GIT_CONFIG_ENV_PREFIXES = ("GIT_CONFIG_KEY_", "GIT_CONFIG_VALUE_")
GIT_TRACE_ENV_PREFIX = "GIT_TRACE"
SAFE_GIT_CONFIG_COUNT = "3"


def child_environment(extra=None, read_only=False):
    """Return a sanitized copy of the environment for one child process.

    ``extra`` values are merged before sanitization so a caller cannot restore a
    blocked Git override. Read-only children disable optional index locks. The
    parent environment is never mutated; token authentication and workflow
    settings such as ``SWARM_GIT_*``, ``GH_TOKEN`` and ``NVD_API_KEY`` survive.
    """
    env = dict(os.environ)
    if extra:
        env.update(extra)
    for name in tuple(env):
        if (name in REPOSITORY_GIT_ENV or name.startswith(GIT_CONFIG_ENV_PREFIXES)
                or name.startswith(GIT_TRACE_ENV_PREFIX)):
            del env[name]
    env["GIT_CONFIG_NOSYSTEM"] = "1"
    env["GIT_CONFIG_GLOBAL"] = "/dev/null"
    env["GIT_NO_REPLACE_OBJECTS"] = "1"
    env["GIT_CONFIG_COUNT"] = SAFE_GIT_CONFIG_COUNT
    env["GIT_CONFIG_KEY_0"] = "safe.directory"
    env["GIT_CONFIG_VALUE_0"] = "*"
    env["GIT_CONFIG_KEY_1"] = "core.hooksPath"
    env["GIT_CONFIG_VALUE_1"] = "/dev/null"
    env["GIT_CONFIG_KEY_2"] = "core.fsmonitor"
    env["GIT_CONFIG_VALUE_2"] = "false"
    if read_only:
        env["GIT_OPTIONAL_LOCKS"] = "0"
    return env
