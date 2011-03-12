/*
 * Copyright 2010 Mario Zechner (contact@badlogicgames.com), Nathan Sweet (admin@esotericsoftware.com)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS"
 * BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */

package com.badlogic.gdx.backends.openal;

import java.nio.FloatBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.LWJGLException;
import org.lwjgl.openal.AL;

import com.badlogic.gdx.Audio;
import com.badlogic.gdx.audio.AudioDevice;
import com.badlogic.gdx.audio.AudioRecorder;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.IntArray;
import com.badlogic.gdx.utils.ObjectMap;
import com.badlogic.gdx.utils.Pool;

import static org.lwjgl.openal.AL10.*;

/**
 * @author Nathan Sweet
 */
public class OpenALAudio implements Audio {
	private IntArray idleStreams, allStreams;
	private ObjectMap<String, Class<? extends OpenALSound>> extensionToSoundClass = new ObjectMap();
	private ObjectMap<String, Class<? extends OpenALMusic>> extensionToMusicClass = new ObjectMap();

	Array<OpenALMusic> music = new Array(false, 1, OpenALMusic.class);

	public OpenALAudio () {
		this(16);
	}

	public OpenALAudio (int simultaneousStreams) {
		registerSound("ogg", Ogg.Sound.class);
		registerMusic("ogg", Ogg.Music.class);
		registerSound("wav", Wav.Sound.class);
		registerMusic("wav", Wav.Music.class);
		registerSound("mp3", Mp3.Sound.class);
		registerMusic("mp3", Mp3.Music.class);

		try {
			AL.create();
		} catch (LWJGLException ex) {
			throw new GdxRuntimeException("Error initializing OpenAL.", ex);
		}

		allStreams = new IntArray(false, simultaneousStreams);
		for (int i = 0; i < simultaneousStreams; i++) {
			int streamID = alGenSources();
			if (alGetError() != AL_NO_ERROR) break;
			allStreams.add(streamID);
		}
		idleStreams = new IntArray(allStreams);

		FloatBuffer orientation = (FloatBuffer)BufferUtils.createFloatBuffer(6)
			.put(new float[] {0.0f, 0.0f, -1.0f, 0.0f, 1.0f, 0.0f}).flip();
		alListener(AL_ORIENTATION, orientation);
		FloatBuffer velocity = (FloatBuffer)BufferUtils.createFloatBuffer(3).put(new float[] {0.0f, 0.0f, 0.0f}).flip();
		alListener(AL_VELOCITY, velocity);
		FloatBuffer position = (FloatBuffer)BufferUtils.createFloatBuffer(3).put(new float[] {0.0f, 0.0f, 0.0f}).flip();
		alListener(AL_POSITION, position);
	}

	public void registerSound (String extension, Class<? extends OpenALSound> soundClass) {
		if (extension == null) throw new IllegalArgumentException("extension cannot be null.");
		if (soundClass == null) throw new IllegalArgumentException("soundClass cannot be null.");
		extensionToSoundClass.put(extension, soundClass);
	}

	public void registerMusic (String extension, Class<? extends OpenALMusic> musicClass) {
		if (extension == null) throw new IllegalArgumentException("extension cannot be null.");
		if (musicClass == null) throw new IllegalArgumentException("musicClass cannot be null.");
		extensionToMusicClass.put(extension, musicClass);
	}

	public OpenALSound newSound (FileHandle file) {
		if (file == null) throw new IllegalArgumentException("file cannot be null.");
		Class<? extends OpenALSound> soundClass = extensionToSoundClass.get(file.extension());
		if (soundClass == null) throw new GdxRuntimeException("Unknown file extension for sound: " + file);
		try {
			return soundClass.getConstructor(new Class[] {OpenALAudio.class, FileHandle.class}).newInstance(this, file);
		} catch (Exception ex) {
			throw new GdxRuntimeException("Error creating sound " + soundClass.getName() + " for file: " + file, ex);
		}
	}

	public OpenALMusic newMusic (FileHandle file) {
		if (file == null) throw new IllegalArgumentException("file cannot be null.");
		Class<? extends OpenALMusic> musicClass = extensionToMusicClass.get(file.extension());
		if (musicClass == null) throw new GdxRuntimeException("Unknown file extension for music: " + file);
		try {
			return musicClass.getConstructor(new Class[] {OpenALAudio.class, FileHandle.class}).newInstance(this, file);
		} catch (Exception ex) {
			throw new GdxRuntimeException("Error creating music " + musicClass.getName() + " for file: " + file, ex);
		}
	}

	int obtainStream (boolean isMusic) {
		for (int i = 0, n = idleStreams.size; i < n; i++) {
			int streamID = idleStreams.get(i);
			int state = alGetSourcei(streamID, AL_SOURCE_STATE);
			if (state != AL_PLAYING && state != AL_PAUSED) {
				if (isMusic) idleStreams.removeIndex(i);
				alSourceStop(streamID);
				alSourcei(streamID, AL_BUFFER, 0);
				return streamID;
			}
		}
		return -1;
	}

	void freeStream (int streamID) {
		alSourceStop(streamID);
		alSourcei(streamID, AL_BUFFER, 0);
		idleStreams.add(streamID);
	}

	void freeBuffer (int bufferID) {
		for (int i = 0, n = idleStreams.size; i < n; i++) {
			int streamID = idleStreams.get(i);
			if (alGetSourcei(streamID, AL_BUFFER) == bufferID) {
				alSourceStop(streamID);
				alSourcei(streamID, AL_BUFFER, 0);
			}
		}
	}

	void stopStreamsWithBuffer (int bufferID) {
		for (int i = 0, n = idleStreams.size; i < n; i++) {
			int streamID = idleStreams.get(i);
			if (alGetSourcei(streamID, AL_BUFFER) == bufferID) alSourceStop(streamID);
		}
	}

	public void update () {
		for (int i = 0; i < music.size; i++)
			music.items[i].update();
	}

	public void dispose () {
		for (int i = 0, n = allStreams.size; i < n; i++) {
			int streamID = allStreams.get(i);
			int state = alGetSourcei(streamID, AL_SOURCE_STATE);
			if (state != AL_STOPPED) alSourceStop(streamID);
			alDeleteSources(streamID);
		}

		AL.destroy();
	}

	public AudioDevice newAudioDevice (boolean isMono) {
		return new JavaSoundAudioDevice(isMono);
	}

	public AudioRecorder newAudioRecoder (int samplingRate, boolean isMono) {
		return new JavaSoundAudioRecorder(samplingRate, isMono);
	}
}
