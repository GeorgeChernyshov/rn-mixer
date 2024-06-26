import { NativeModules, Platform } from 'react-native';

const LINKING_ERROR =
  `The package 'armsaudio' doesn't seem to be linked. Make sure: \n\n` +
  Platform.select({ ios: "- You have run 'pod install'\n", default: '' }) +
  '- You rebuilt the app after installing the package\n' +
  '- You are not using Expo Go\n';

const Armsaudio = NativeModules.Armsaudio
  ? NativeModules.Armsaudio
  : new Proxy(
    {},
    {
      get() {
        throw new Error(LINKING_ERROR);
      },
    }
  );

export const newAddon = () => Armsaudio
