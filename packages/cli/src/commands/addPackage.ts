import fetch from 'node-fetch';
import shell from 'shelljs';
import checkAndGetInstaller from '../utils/checkAndGetInstaller';
import installPeersDependencies from '../utils/installPeersDependencies';
import addReactNativeGestureHandlerImport from '../utils/addReactNativeGestureHandlerImport';
import getLogger from '../utils/logger';

const logger = getLogger();

const installPackage = async (pack: string): Promise<any> => {
  /**
   * Fetching package meta data
   */
  logger.log('Fetching package metadata ...');
  const [packName, version = 'latest'] = pack.split('@');

  let metaData;

  try {
    let response = await fetch(
      `https://registry.npmjs.org/@react-navigation/${packName}/${version}`
    );

    metaData = await response.json();

    logger.verbose('Meta data');
    logger.verbose(metaData);
  } catch (e) {
    logger.error('Please enter a valid React-Navigation Package');
  }

  /**
   * Get installer after check
   * - Use expo if we find expo in the dependencies of package.json
   * - Use yarn if the user has yarn.lock file
   * - Use npm if the user has package-json.lock
   * - If no lockfile is present, use yarn if it's installed, otherwise npm
   * */
  const {
    installer,
    state: getInstallerState,
    rootDirectory,
  } = checkAndGetInstaller(process.cwd());

  logger.verbose(`Installer: ${installer}\nrootDirectory: ${rootDirectory}`);
  logger.debug({ installer, getInstallerState, rootDirectory });

  /**
   * No installer
   */
  if (!installer) {
    if (!getInstallerState.isPackageJsonFound) {
      throw new Error(
        'No root project was found! No package.json was found. Make sure your project already have one.'
      );
    }

    if (!getInstallerState.isNpmInstalled) {
      throw new Error(
        'Npm not installed. Make sure you have a working node installation and npm! And try again!'
      );
    }
  }

  /**
   * Install package
   */
  const install = (command: string): void => {
    logger.log(command);
    shell.exec(command);
  };

  shell.cd(rootDirectory as string);

  let versionStr = '';
  if (installer !== 'expo') {
    versionStr = version ? `@${version.replace(/ /g, '')}` : '';
  }

  if (['expo', 'yarn'].includes(installer as string)) {
    // expo and yarn
    install(
      `npx ${installer} add @react-navigation/${packName}${versionStr}${
        installer === 'yarn' ? ` --save` : ''
      }`
    );
  } else {
    // npm
    install(
      `${installer} install @react-navigation/${packName}${versionStr} --save`
    );
  }

  /**
   * Install dependencies
   */
  installPeersDependencies(metaData, installer as string);

  /**
   * Add `import ${quote}react-native-gesture-handler${quote}` to index.(ts|js)
   */
  if (
    metaData.peerDependencies?.hasOwnProperty('react-native-gesture-handler')
  ) {
    logger.log('Package have react-native-gesture-handler as peer dependency!');
    addReactNativeGestureHandlerImport(rootDirectory as string);
  }
};

export default installPackage;
