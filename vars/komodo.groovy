def releaseDeployed(_PREFIX, _RELEASE_NAME) {
    sh """
        if [ -e $_PREFIX/$_RELEASE_NAME ]; then
            echo ${_RELEASE_NAME} is already deployed at ${_PREFIX}/${_RELEASE_NAME}!
            exit 1
        fi
    """
}

def configureGit(_GIT_EXEC) {
    sh """
        # fix error:
        # RPC failed; curl 56
        # SSL read: errno -5961
        # fatal: The remote end hung up unexpectedly
        # fatal: early EOF
        # fatal: index-pack failed
        ## start git config
        ## https://stackoverflow.com/questions/6842687
        $_GIT_EXEC config --global http.postBuffer 1048576000
        ## end
    """
}

def checkoutGitBranch(_GIT_EXEC, _GIT_REF) {
    sh """
        $_GIT_EXEC checkout $_GIT_REF
    """
}

def installKomodo(_BUILD_PYTHON, _BUILD_PYTHON_ENV) {
    sh """
        $_BUILD_PYTHON/bin/python3 -m venv $_BUILD_PYTHON_ENV
        $_BUILD_PYTHON_ENV/bin/python -m pip install .
        $_BUILD_PYTHON_ENV/bin/python -c "import komodo; print(komodo.__file__)"
    """
}

def cloneAndCheckoutKomodoConfig(_GIT_EXEC, _CONFIG_GIT_FORK, _CONFIG_GIT_REF, _TOKEN) {
    sh """
        $_GIT_EXEC clone https://${_TOKEN}@github.com/${_CONFIG_GIT_FORK}/komodo-releases.git
        pushd komodo-releases
        $_GIT_EXEC checkout $_CONFIG_GIT_REF
        popd
    """
}

def copyScripts(_KOMODO_ROOT, _KOMODO_RELEASES_ROOT) {
    // NOTE: This is to backwards compatible with the old komodo-releases setup
    // Should be removed as soon as we have moved all building to the komodo
    // instance
    sh """
        mkdir $_KOMODO_RELEASES_ROOT/src
        cp $_KOMODO_ROOT/setup-py.sh $_KOMODO_RELEASES_ROOT/src
        cp $_KOMODO_ROOT/enable.m4 $_KOMODO_RELEASES_ROOT
        cp $_KOMODO_ROOT/enable.in $_KOMODO_RELEASES_ROOT
        cp $_KOMODO_ROOT/enable.csh.in $_KOMODO_RELEASES_ROOT
    """
}

def validateRelease(_BUILD_PYTHON, _KOMODO_RELEASES_ROOT, _PACKAGES, _REPOSITORY) {
    sh """
        pushd $_KOMODO_RELEASES_ROOT

        # lint first
        $_BUILD_PYTHON -m komodo.lint $_PACKAGES $_REPOSITORY

        # output maintainers
        $_BUILD_PYTHON -m komodo.maintainer $_PACKAGES $_REPOSITORY

        popd
    """
}

def buildAndInstallRelease(_BUILD_PYTHON, _REPOSITORY, _RELEASE_FILE, _RELEASE_NAME, _KOMODO_RELEASES_ROOT, _PREFIX, _PIPELINE_STEPS, _DEVTOOLSET, _PIP_EXEC, _CMAKE_EXECUTABLE, _GIT_EXEC, _PERMISSIONS_EXEC) {
    sh """
        source $_DEVTOOLSET
        set -xe

        pushd $_KOMODO_RELEASES_ROOT
        $_BUILD_PYTHON -m komodo.cli $_RELEASE_FILE $_REPOSITORY \
            --jobs 6                                             \
            --release $_RELEASE_NAME                             \
            --tmp tmp                                            \
            --cache cache                                        \
            --prefix $_PREFIX                                    \
            --cmake $_CMAKE_EXECUTABLE                           \
            --pip $_PIP_EXEC                                     \
            --git $_GIT_EXEC                                     \
            --postinst $_PERMISSIONS_EXEC                        \
            $_PIPELINE_STEPS                                     \

        popd
    """
}

def installLocalFiles(_KOMODO_RELEASES_ROOT, _PREFIX, _RELEASE_NAME, _PERMISSIONS_EXEC) {
    sh """
        pushd $_KOMODO_RELEASES_ROOT
        # Here we *very manually* copy the files local/local and local/local.csh to
        # the location of the main enable file. Dang - this is quite ugly ....
        if [ -e local/local ]; then
           cp local/local $_PREFIX/$_RELEASE_NAME/local
           $_PERMISSIONS_EXEC $_PREFIX/$_RELEASE_NAME/local
        fi

        if [ -e local/local.csh ]; then
           cp local/local.csh $_PREFIX/$_RELEASE_NAME/local.csh
           $_PERMISSIONS_EXEC $_PREFIX/$_RELEASE_NAME/local.csh
        fi
        popd
    """
}

def call(args) {
    pipeline {
        agent { label args.agent_labels }
        environment {
            CONFIG_TOKEN = credentials("${args.config_token_name}")
            PIPELINE_STEPS = "${args.deploy == "true" ? "--download --build --install" : "--dry-run --download --build"}"
            PY_VER_MAJOR = "${args.python_version.split("\\.")[0]}"
            PY_VER_MINOR = "${args.python_version.split("\\.")[1]}"
            RELEASE_NAME = "${args.release_base}-py${env.PY_VER_MAJOR}${env.PY_VER_MINOR}"
            RELEASE_FILE = "releases/${env.RELEASE_NAME}.yml"
            REPOSITORY = "repository.yml"
            BUILD_ENV_DIR = "${env.WORKSPACE}/build-env"
            BUILD_PYTHON = "${env.BUILD_ENV_DIR}/bin/python"
            KOMODO_ROOT = "${env.WORKSPACE}"
            KOMODO_RELEASES_ROOT = "${env.WORKSPACE}/komodo-releases"
        }
        stages {
            stage('Already deployed') {
                when {
                    expression {
                        return env.overwrite != 'true';
                    }
                }
                steps {
                    script {
                        releaseDeployed(env.PREFIX, env.RELEASE_NAME)
                    }
                }
            }
            stage('Configure git') {
                steps {
                    script {
                        configureGit(env.GIT_EXEC)
                    }
                }
            }
            stage('Checkout Komodo branch') {
                steps {
                    script {
                        checkoutGitBranch(env.GIT_EXEC, env.CODE_GIT_REF)
                    }
                }
            }
            stage('Install Komodo') {
                steps {
                    script {
                        installKomodo(args.build_python, env.BUILD_ENV_DIR)
                    }
                }
            }
            stage('Clone and checkout Komodo config') {
                steps {
                    script {
                        cloneAndCheckoutKomodoConfig(env.GIT_EXEC, env.CONFIG_GIT_FORK, env.CONFIG_GIT_REF, env.CONFIG_TOKEN)
                    }
                }
            }
            stage('Copy scripts') {
                steps {
                    script {
                        copyScripts(env.KOMODO_ROOT, env.KOMODO_RELEASES_ROOT)
                    }
                }
            }
            stage('Validate release') {
                steps {
                    script {
                        validateRelease(env.BUILD_PYTHON, env.KOMODO_RELEASES_ROOT, env.RELEASE_FILE, env.REPOSITORY)
                    }
                }
            }
            stage('Build and Install') {
                steps {
                    script {
                        System.setProperty("org.jenkinsci.plugins.durabletask.BourneShellScript.HEARTBEAT_CHECK_INTERVAL", "80000");
                    }
                    script {
                        PIP_EXEC = "${args.target_python}/bin/pip"
                        buildAndInstallRelease(env.BUILD_PYTHON, env.REPOSITORY, env.RELEASE_FILE, env.RELEASE_NAME, env.KOMODO_RELEASES_ROOT, env.PREFIX, env.PIPELINE_STEPS, env.DEVTOOLSET, PIP_EXEC, env.CMAKE_EXECUTABLE, env.GIT_EXEC, env.PERMISSIONS_EXEC)
                    }
                }
            }
            stage('Copy local files') {
                when {
                    expression {
                        return args.deploy == 'true';
                    }
                }
                steps {
                    script {
                        installLocalFiles(env.KOMODO_RELEASES_ROOT, env.PREFIX, env.RELEASE_NAME, env.PERMISSIONS_EXEC)
                    }
                }
            }
        }
        post {
            always {
                cleanWs()
            }
            success {
                build job: 'komodo-suggest-symlink', parameters: [
                    string(name: 'RELEASE', value: env.RELEASE_NAME),
                    string(name: 'MODE', value: 'unstable')
                ], wait: false
                build job: 'komodo-test', parameters: [
                    string(name: 'RELEASE', value: env.RELEASE_NAME),
                ], wait: false
            }
            failure {
                slackSend color: "#f02e2e", message: "Building komodo release ${env.RELEASE_NAME} failed (<${env.BUILD_URL}|Open>)"
            }
        }
    }
}
