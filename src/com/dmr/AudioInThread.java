package com.dmr;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import javax.swing.JOptionPane;

public class AudioInThread extends Thread {
	private boolean run;
	private boolean audioReady;
	private int writePos;
	private int readPos;
	private int lastWritePos;
	// A 256 K Byte buffer seems to do the job
	private static int BUFFERSIZE=256*1024;
	private int audioBuffer[]=new int[BUFFERSIZE];
	private TargetDataLine Line;
	private AudioFormat format;
	private boolean gettingAudio;
	private static int VOLUMEBUFFERSIZE=100;
	private int volumeBuffer[]=new int[VOLUMEBUFFERSIZE];
	private int volumeBufferCounter=0;
	private static int ISIZE=128;
	private byte buffer[]=new byte[ISIZE+1];
	
	// Filter details ..
	// filtertype	 =	 Raised Cosine
	// samplerate	 =	 48000
	// corner	 =	 2400
	// beta	 =	 0.2 
	// impulselen	 =	 81
	// racos	 =	 sqrt
	// comp	 =	 no
	// bits	 =	
	// logmin	 =	
	private static int NZEROS=80;
	private double xv[]=new double [NZEROS+1];
	// 0.2 //
	private static double GAIN=9.868410946e+00;
	private static double XCOEFFS[]=
	  {+0.0273676736, +0.0190682959, +0.0070661879, -0.0075385898,
	    -0.0231737159, -0.0379433607, -0.0498333862, -0.0569528373,
	    -0.0577853377, -0.0514204905, -0.0377352004, -0.0174982391,
	    +0.0076217868, +0.0351552125, +0.0620353691, +0.0848941519,
	    +0.1004237235, +0.1057694293, +0.0989127431, +0.0790009892,
	    +0.0465831968, +0.0037187043, -0.0460635022, -0.0979622825,
	    -0.1462501260, -0.1847425896, -0.2073523972, -0.2086782295,
	    -0.1845719273, -0.1326270847, -0.0525370892, +0.0537187153,
	    +0.1818868577, +0.3256572849, +0.4770745929, +0.6271117870,
	    +0.7663588857, +0.8857664963, +0.9773779594, +1.0349835419,
	    +1.0546365475, +1.0349835419, +0.9773779594, +0.8857664963,
	    +0.7663588857, +0.6271117870, +0.4770745929, +0.3256572849,
	    +0.1818868577, +0.0537187153, -0.0525370892, -0.1326270847,
	    -0.1845719273, -0.2086782295, -0.2073523972, -0.1847425896,
	    -0.1462501260, -0.0979622825, -0.0460635022, +0.0037187043,
	    +0.0465831968, +0.0790009892, +0.0989127431, +0.1057694293,
	    +0.1004237235, +0.0848941519, +0.0620353691, +0.0351552125,
	    +0.0076217868, -0.0174982391, -0.0377352004, -0.0514204905,
	    -0.0577853377, -0.0569528373, -0.0498333862, -0.0379433607,
	    -0.0231737159, -0.0075385898, +0.0070661879, +0.0190682959,
	    +0.0273676736};

    public AudioInThread (DMRDecode theApp) {
    	run=false;
    	audioReady=false;
    	writePos=0;
    	readPos=0;
    	lastWritePos=-1;
    	gettingAudio=false;
    	setPriority(Thread.MIN_PRIORITY);
        start();
        Thread.yield();
      }
    
    // Main
    public void run()	{
    	// Run continously
    	for (;;)	{
    		// If it hasn't been already then setup the audio device
    		if (audioReady==false) setupAudio();
    		// If the audio device is ready , the program wants to and we aren't already then
    		// get data from the audio device.
    		if ((audioReady==true)&&(run==true)&&(gettingAudio==false)) getSample();
     	}
    }
	
    // Prepare the input audio device
    private void setupAudio ()	{
		  try {
			  // Sample at 48000 Hz , 16 bit samples , 1 channel , signed with bigendian numbers
			  format=new AudioFormat(48000,16,1,true,true);
			  DataLine.Info info=new DataLine.Info(TargetDataLine.class,format);
			  Line=(TargetDataLine) AudioSystem.getLine(info);
			  Line.open(format);
			  Line.start();
			  audioReady=true;
		  } catch (Exception e) {
			  JOptionPane.showMessageDialog(null, "Fatal error in setupAudio()","DMRdecoder", JOptionPane.ERROR_MESSAGE);
			  System.exit(0);
	   		}
    }
    
    // Read in 2 bytes from the audio source combine them together into a single integer
    // then write that into the sound buffer
    private void getSample ()	{
    	// Tell the main thread we getting audio
    	gettingAudio=true;
    	int a,sample,count,total=0;
    	// READ in ISIZE bytes and convert them into ISIZE/2 integers
    	// Doing it this way reduces CPU loading
		try	{
				while (total<ISIZE)	{
					count=Line.read(buffer,0,ISIZE);
					total=total+count;
			  		}
			  	} catch (Exception e)	{
			  		String err=e.getMessage();
			  		JOptionPane.showMessageDialog(null,err,"DMRDecode", JOptionPane.ERROR_MESSAGE);
			  	}
		// Get the required number of samples
		for (a=0;a<ISIZE;a=a+2)	{
		sample=(buffer[a]<<8)+buffer[a+1];
		// Add this sample to the circular volume buffer
		addToVolumeBuffer(sample);
		// Put this through a root raised filter
		// then put the filtered sample in the buffer
		audioBuffer[writePos]=rootRaisedFilter(sample);
		// Increment the write buffer pos
		writePos++;
		// If the write buffer pointer has reached maximum then reset it to zero
		if (writePos==BUFFERSIZE) writePos=0;
		}
		// The the main thread we have stopped fetching audio
		gettingAudio=false;	
    }
    
    // Return the next integer from the sound buffer
    // then increment the read buffer counter
    public int returnSample ()	{
    	if (run==false) return -1;
    	// If the writePos hasn't changed since the last time then there is nothing to return
    	if (writePos==lastWritePos)	{
    		String err="AudioInThread returnSample() has caught up with itself !";
    		JOptionPane.showMessageDialog(null,err,"DMRDecode", JOptionPane.ERROR_MESSAGE);
    		System.exit(0);
    	}
    	int sample=audioBuffer[readPos];
    	lastWritePos=writePos;
    	// Increment the read buffer counter
    	readPos++;
    	// If the read buffer pointer has reached maximum then reset it to zero
    	if (readPos==BUFFERSIZE) readPos=0;
    	return sample;
    }
    
    // Called when the main program wants to start receiving audio
    public void startAudio ()	{
    	run=true;
    }
    
    // Getter tells the main program if the audio device is ready
    public boolean getAudioReady ()	{
    	return audioReady;
    }
    
    // When called this closes the audio device
    public void shutDownAudio ()	{
    	run=false;
    	Line.close();
    }
    
    // A root raised pulse shaping filter
    public int rootRaisedFilter (int sample)	{
    	int i;
    	double sum=0.0;
    	double in=(double) sample;
    	for (i=0;i<NZEROS;i++)	{
    		xv[i]=xv[i+1];
    	}
    	xv[NZEROS]=in/GAIN;
    	for (i=0;i<=NZEROS;i++)	{
    		sum=sum+(XCOEFFS[i]*xv[i]);
    	}
    	return (int)sum;
    }

    // Return true only if there are samples waiting
    public boolean sampleReady ()	{
    	if (writePos==lastWritePos) return false;
    	 else return true;
    }
    
    // Add this sample to the circular volume buffer
    private void addToVolumeBuffer (int tsample)	{
    	volumeBuffer[volumeBufferCounter]=tsample;
    	volumeBufferCounter++;
    	if (volumeBufferCounter==VOLUMEBUFFERSIZE) volumeBufferCounter=0;
    }
    
    // Return the average volume over the last VOLUMEBUFFERSIZE samples
    public int returnVolumeAverage ()	{
    	long va=0;
    	int a,volumeAverage=0;
    	for (a=0;a<VOLUMEBUFFERSIZE;a++)	{
    		va=va+Math.abs(volumeBuffer[a]);
    	}
    	volumeAverage=(int)va/VOLUMEBUFFERSIZE;	
    	return volumeAverage;
    }
}
