/**
 * 
 */
package farom.plamdriver;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;

import org.usb4java.ConfigDescriptor;
import org.usb4java.Context;
import org.usb4java.Device;
import org.usb4java.DeviceDescriptor;
import org.usb4java.DeviceHandle;
import org.usb4java.DeviceList;
import org.usb4java.LibUsb;
import org.usb4java.LibUsbException;
import org.usb4java.Transfer;
import org.usb4java.TransferCallback;

/*import javax.usb.UsbClaimException;
 import javax.usb.UsbConfiguration;
 import javax.usb.UsbConst;
 import javax.usb.UsbControlIrp;
 import javax.usb.UsbDevice;
 import javax.usb.UsbDeviceDescriptor;
 import javax.usb.UsbDisconnectedException;
 import javax.usb.UsbEndpoint;
 import javax.usb.UsbEndpointDescriptor;
 import javax.usb.UsbException;
 import javax.usb.UsbHostManager;
 import javax.usb.UsbHub;
 import javax.usb.UsbInterface;
 import javax.usb.UsbNotActiveException;
 import javax.usb.UsbPipe;
 import javax.usb.UsbServices;*/

import nom.tam.fits.Fits;
import nom.tam.fits.FitsException;
import nom.tam.fits.FitsFactory;
import nom.tam.util.BufferedFile;

/**
 * @author farom
 * 
 */
public class PlamDriver implements TransferCallback {
	private static final short VENDOR_ID = 0x0547;
	private static final short PRODUCT_ID = 0x3303;
	private static final long CTRL_TIMEOUT = 100;
	private static final long IMG_TIMEOUT = 1000;
	private static final int IMG_BYTE_SIZE = 640 * 480 * 2;
	private static final int IMG_PACKET_MAXSIZE = IMG_BYTE_SIZE;
	private static final byte BULK_ENDPOINT = (byte) 0x82;
	private static final double DURATION_COEF = 5923;// 7692;//59171.5978836;
	private static final short IMGCTRL_GAIN = 0;
	private static final short IMGCTRL_BLACK = 1;
	private static final short IMGCTRL_THIGH = 6;
	private static final short IMGCTRL_TLOW = 7;
	

	private Device device;
	private DeviceHandle handle;
	private EventHandlingThread thread;
	private Context context;
	
	private byte raw[];

	private boolean closed = false;
	private boolean connected = false;
	private boolean captureOngoing;
	private int offset;

	/**
	 * 
	 */
	public PlamDriver() {

		// starting libusb and the associated thread
		context = new Context();
		int result = LibUsb.init(context);
		//LibUsb.setDebug(context,LibUsb.LOG_LEVEL_DEBUG);
		thread = new EventHandlingThread();
		thread.start();
		if (result != LibUsb.SUCCESS) {
			System.out.println("Unable to initialize libusb: " + result);
			return;
		}

	}

	/**
	 * Disconnecting the camera
	 */
	public void disconnect(){
		if (connected) {
			int result = LibUsb.releaseInterface(handle, 0);
			if (result != LibUsb.SUCCESS)
				throw new LibUsbException("Unable to release interface", result);
			LibUsb.close(handle);
			System.out.println("Camera disconnected");
			connected = false;
		}
	}
	
	/**
	 * Closing safely the usb related stuff, disconnecting the camera
	 */
	public void close() {
		disconnect();

		thread.abort();
		try {
			thread.join();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		LibUsb.exit(context);
		System.out.println("LibUsb stopped");
	}

	@Override
	public void finalize() {
		if (!closed) {
			close();
			System.out.println("Call to finalize when not closed");
		}

	}

	/**
	 * Connects and initializes the camera
	 * @throws LibUsbException
	 */
	public void connect() throws LibUsbException {
		device = findDevice(VENDOR_ID, PRODUCT_ID);
		if (device == null)
			throw new LibUsbException("Device not found", LibUsb.ERROR_NOT_FOUND);

		System.out.println("Device found");

		handle = new DeviceHandle();
		int result = LibUsb.open(device, handle);
		if (result != LibUsb.SUCCESS)
			throw new LibUsbException("Unable to open USB device", result);

		System.out.println("Device opened");

		result = LibUsb.claimInterface(handle, 0);
		if (result != LibUsb.SUCCESS)
			throw new LibUsbException("Unable to claim interface", result);

		System.out.println("Connection established");
		connected = true;

		init();

	}

	/**
	 * Return the device with the given vendor and product IDs
	 * @param vendorId
	 * @param productId
	 * @return
	 * @throws LibUsbException
	 */
	private Device findDevice(short vendorId, short productId) throws LibUsbException {
		// Read the USB device list
		DeviceList list = new DeviceList();
		int result = LibUsb.getDeviceList(null, list);
		if (result < 0)
			throw new LibUsbException("Unable to get device list", result);

		try {
			// Iterate over all devices and scan for the right one
			for (Device device : list) {
				DeviceDescriptor descriptor = new DeviceDescriptor();
				result = LibUsb.getDeviceDescriptor(device, descriptor);
				if (result != LibUsb.SUCCESS)
					throw new LibUsbException("Unable to read device descriptor", result);
				if (descriptor.idVendor() == vendorId && descriptor.idProduct() == productId)
					return device;
			}
		} finally {
			// Ensure the allocated device list is freed
			LibUsb.freeDeviceList(list, true);
		}

		// Device not found
		return null;
	}

	/**
	 * Send initialization commands
	 */
	private void init() {
		// golden pass
		sendAndVerifyControl(0xc0, 17, 8190, 0x0000, new byte[] { 0x00, 0x05, 0x08 });
		sendAndVerifyControl(0xc0, 17, 9216, 0x0000, new byte[] { 0x30, 0x36, 0x08 });
		sendAndVerifyControl(0xc0, 17, 9218, 0x0000, new byte[] { 0x31, 0x31, 0x08 });
		sendAndVerifyControl(0xc0, 17, 9220, 0x0000, new byte[] { 0x30, 0x39, 0x08 });
		sendAndVerifyControl(0xc0, 17, 9222, 0x0000, new byte[] { 0x30, 0x30, 0x08 });
		sendAndVerifyControl(0xc0, 17, 9224, 0x0000, new byte[] { 0x30, 0x36, 0x08 });
		sendAndVerifyControl(0xc0, 17, 9226, 0x0000, new byte[] { 0x32, 0x00, 0x08 });
		sendAndVerifyControl(0x40, 9, 256, 0x000f, new byte[0]);
		try {
			Thread.sleep(100); // 100 milliseconds
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		sendAndVerifyControl(0x40, 9, 512, 0x000f, new byte[0]);
		sendAndVerifyControl(0xc0, 17, 8190, 0x0000, new byte[] { 0x00, 0x05, 0x08 });
		sendAndVerifyControl(0x40, 9, 1024, 0x0001, new byte[0]);
		sendAndVerifyControl(0x40, 3, 8963, 0x0001, new byte[0]);//sendImgCtrl(3, 1);
		try {
			Thread.sleep(100); // 100 milliseconds
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		sendAndVerifyControl(0x40, 3, 13059, 0x0000, new byte[0]);
		sendAndVerifyControl(0x40, 3, 8707, 0x0001, new byte[0]);
		sendAndVerifyControl(0x40, 3, 13571, 0x0000, new byte[0]);
		sendAndVerifyControl(0x40, 3, 50532, 0x067f, new byte[0]);
		sendAndVerifyControl(0x40, 3, 13699, 0x0800, new byte[0]);
		sendAndVerifyControl(0x40, 3, 50654, 0x0ddf, new byte[0]);
		sendAndVerifyControl(0x40, 9, 1024, 0x0000, new byte[0]);
		sendAndVerifyControl(0x40, 9, 1024, 0x0001, new byte[0]);

		sendAndVerifyControl(0x40, 3, 13443, 0x800, new byte[0]);
		sendAndVerifyControl(0x40, 9, 768, 0x0000, new byte[0]);
		sendAndVerifyControl(0x40, 13, 0, 0x0001, new byte[0]);
		sendAndVerifyControl(0x40, 13, 0, 0x0001, new byte[0]);
		sendAndVerifyControl(0x40, 3, 13827, 0x0000, new byte[0]);
		sendAndVerifyControl(0x40, 3, 10035, 0x0301, new byte[0]);

		sendAndVerifyControl(0x40, 3, 53256, 0x00be, new byte[0]);
		sendAndVerifyControl(0x40, 3, 12547, 0x0000, new byte[0]);

		sendAndVerifyControl(0x40, 3, 13443, 0x0800, new byte[0]);
		sendAndVerifyControl(0x40, 9, 768, 0x0000, new byte[0]);
		sendAndVerifyControl(0x40, 13, 0, 0x0001, new byte[0]);
		sendAndVerifyControl(0x40, 13, 0, 0x0001, new byte[0]);
		sendAndVerifyControl(0x40, 5, 15, 0x0003, new byte[0]);
		/*capture(1,-1);

		sendAndVerifyControl(0x40, 3, 13827, 0x0000, new byte[0]);
		sendAndVerifyControl(0x40, 3, 10035, 0x0301, new byte[0]);

		while (captureOngoing == true) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			System.out.print(".");
		}*/
		setGain(750);
		System.out.println("Camera initialized");
		

		
	}

	/**
	 * abort the current capture
	 */
	public void abort(){
		// TODO
	}
	
	/**
	 * return true if it is ready to capture
	 * @return
	 */
	public boolean ready(){
		// TODO
		return !captureOngoing;
	}
	
	/**
	 * Take n images with the given exposure time, it returns immediately
	 * @param n number of exposure
	 * @param time exposure time in sec
	 */
	public void capture(int n, final double time) {
		captureOngoing = false;
		raw = new byte[IMG_BYTE_SIZE];
		offset = 0;
		requestTransfer(IMG_PACKET_MAXSIZE, IMG_TIMEOUT);
//		while(captureOngoing){
//			try {
//				Thread.sleep(1);
//			} catch (InterruptedException e) {
//				e.printStackTrace();
//			}
//		}		
		captureOngoing = true;
		offset = 0;
		
		if(time>0){
			// Sending exposure duration;
			int time_int = (int) (time * DURATION_COEF);
			short time_high = (short) (time_int / 4096);
			short time_low = (short) (time_int - time_high * 4096);
			sendImgCtrl(IMGCTRL_THIGH, time_high);
			sendImgCtrl(IMGCTRL_TLOW, time_low);
		}
		

		
		
		Timer timer = new Timer();
		timer.schedule(new TimerTask() {			
			@Override
			public void run() {
				requestTransfer(IMG_PACKET_MAXSIZE, IMG_TIMEOUT);				
			}
		}, Math.max(0,(long) (time * 1000 - 10)));
		//requestTransfer(IMG_PACKET_MAXSIZE, time);
		//sendImgCtrl(IMGCTRL_TLOW, time_low);
		//captureOngoing = true;
		
		
		System.out.println("Exposure started");

		
		
	}

	/**
	 * Send Usb control request and verify the answer, return true if the answer is correct
	 * @param bmRequestType
	 * @param bRequest
	 * @param wIndex
	 * @param wValue
	 * @param trueData
	 * @return
	 */
	private boolean sendAndVerifyControl(int bmRequestType, int bRequest, int wIndex, int wValue, byte[] trueData) {
		byte[] data = new byte[1];
		try {
			data = sendControl((byte) bmRequestType, (byte) bRequest, (short) wIndex, (short) wValue,
					(short) trueData.length);
		} catch (LibUsbException e) {
			System.out.println("Exception raised for the following command:");
			System.out.println("  bmRequestType=" + bmRequestType);
			System.out.println("  bRequest=" + bRequest);
			System.out.println("  wIndex=" + wIndex);
			System.out.println("  wValue=" + wValue);
			System.out.println("  Exception message: " + e.getLocalizedMessage());
			return false;
		}
		if (!Arrays.equals(data, trueData)) {
			System.out.println("Wrong answer to the following command:");
			System.out.println("  bmRequestType=" + bmRequestType);
			System.out.println("  bRequest=" + bRequest);
			System.out.println("  wIndex=" + wIndex);
			System.out.println("  wValue=" + wValue);
			System.out.println("  Received:");
			for (int i = 0; i < data.length; i++) {
				System.out.printf("%02X ", data[i]);
			}
			System.out.println("");
			System.out.println("  Instead of:");
			for (int i = 0; i < trueData.length; i++) {
				System.out.printf("%02X ", trueData[i]);
			}
			System.out.println("");
			return false;
		} else {
			return true;
		}
	}

	/**
	 * Send a control request and return the answer if any
	 * @param bmRequestType
	 * @param bRequest
	 * @param wIndex
	 * @param wValue
	 * @param dataLength
	 * @return
	 * @throws LibUsbException
	 */
	private byte[] sendControl(byte bmRequestType, byte bRequest, short wIndex, short wValue, short dataLength)
			throws LibUsbException {
		if (bmRequestType >= 0 && dataLength != 0) { // host to device request
														// with undefined data
			System.out.println("host to device request with undefined data: bmRequestType = " + bmRequestType
					+ " and dataLength = " + dataLength);
		}

		ByteBuffer buffer = ByteBuffer.allocateDirect(dataLength);
		int transfered = LibUsb.controlTransfer(handle, bmRequestType, bRequest, wValue, wIndex, buffer, CTRL_TIMEOUT);
		if (transfered != dataLength)
			throw new LibUsbException("Control transfer failed", transfered);

		if (dataLength > 0) {
			byte array[] = new byte[dataLength];
			for (int i = 0; i < dataLength; i++) {
				array[i] = buffer.get(i);
			}
			return array;
		} else {
			return new byte[0];
		}
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		PlamDriver driver = new PlamDriver();
		driver.connect();
		driver.setGain(250);
		driver.capture(1,0.05);
		while(!driver.ready()){
			try {
				Thread.sleep(1);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		
		try {
			Thread.sleep(1500);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		driver.close();
		System.out.println("end");
		System.exit(0);

	}

	/**
	 * Send a command to change an image parameter
	 * @param type_int	index of the parameter (See IMGCTRL_XXX constants)
	 * @param value_int	value of the parameter
	 */
	private void sendImgCtrl(int type_int, int value_int) {
		short value = (short) value_int;
		short type = (short) type_int;
		
		short wIndex = (short) ((short) ((value ^ (short) 0x33) << 4) | type); // kind of checksum
		
		//System.out.printf("wIndex0 = %04X type = %04X\n", wIndex,type);
		wIndex = (short) (wIndex << 8 | ((wIndex >> 8) & 0xFF));
		//System.out.printf("value = %04X wIndex = %04X\n", value, wIndex);
		sendControl((byte) 0x40, (byte) 0x3, wIndex, value, (short) 0);
	}
	
	/**
	 * Change the camera gain
	 * @param gain
	 */
	private void setGain(int gain) {
		if (gain > 1023 || gain < 0) {
			throw new IllegalArgumentException("The gain must be between 0 and 1023. Current value: " + gain);
		}
		sendImgCtrl(IMGCTRL_GAIN, gain);
	}

	/**
	 * Change the camera black level
	 * @param level
	 */
	private void setBlack(int level) {
		if (level > 255 || level < 0) {
			throw new IllegalArgumentException("The black level must be between 0 and 255. Current value: " + level);
		}
		sendImgCtrl(IMGCTRL_BLACK, level);
	}

	class EventHandlingThread extends Thread {
		/** If thread should abort. */
		private volatile boolean abort;

		/**
		 * Aborts the event handling thread.
		 */
		public void abort() {
			this.abort = true;
		}

		@Override
		public void run() {
			while (!this.abort) {
				int result = LibUsb.handleEventsTimeout(null,100);
				if (result != LibUsb.SUCCESS)
					throw new LibUsbException("Unable to handle events", result);
			}
			System.out.println("Thread aborted");
		}
	}

	/**
	 * Callback for incoming data
	 */
	@Override
	public void processTransfer(Transfer transfer) {


        System.out.println("Bulk transfer: "+transfer.actualLength()+"/"+transfer.length()+" status: " + LibUsb.errorName(transfer.status()));
        
        
//        if(transfer.actualLength()==0){
//        	captureOngoing = false;
//        	System.out.println("Error: 0 byte, "+LibUsb.errorName(transfer.status())+" "+offset+"/"+IMG_BYTE_SIZE);
//        	return;
//        }
        if(transfer.actualLength()==IMG_BYTE_SIZE){        
	        transfer.buffer().get(raw, offset, Math.min(transfer.actualLength(),IMG_BYTE_SIZE-offset));
	        offset += Math.min(transfer.actualLength(),IMG_BYTE_SIZE-offset);
	        captureOngoing = false;
        	System.out.println("Image received");
        	
        	float[][] data1 = new float[480][640];
    		for(int i = 0; i<480; i++){
    			for(int j = 0; j<640; j++){
    				data1[i][j]=(raw[(i*640+(639-j))*2]&0xFF)*256+(raw[(i*640+(639-j))*2+1]&0xFF);//i+2*j;//256.f*(data[(i*640+j)*2]&0xFF)+1.f*(data[(i*640+j)*2+1]&0xFF);//+data[(i*640+j)*2+1]&0xFF;
    				
    			}
    		}
    		
    		try {
    			Fits f1 = new Fits();
    			f1.addHDU(FitsFactory.HDUFactory(data1));
    			BufferedFile bf1 = new BufferedFile("img1.fits", "rw");
    			f1.write(bf1);
    			bf1.close();
    			//f1.close();
    			System.out.println("image saved!");   			
    			
    		} catch (FitsException e) {
    			// TODO Auto-generated catch block
    			e.printStackTrace();
    		} catch (IOException e) {
    			// TODO Auto-generated catch block
    			e.printStackTrace();
    		}
    		
    		
        }else if(captureOngoing){
        	requestTransfer(IMG_PACKET_MAXSIZE, IMG_TIMEOUT);
        }
        
        LibUsb.freeTransfer(transfer);

        


	}
	
	
	private void requestTransfer(int size, long timeout){
		ByteBuffer buffer = ByteBuffer.allocateDirect(IMG_PACKET_MAXSIZE);
		Transfer transfer = LibUsb.allocTransfer();
		LibUsb.fillBulkTransfer(transfer, handle, BULK_ENDPOINT, buffer, this, null, timeout);
		transfer.setLength(size);
		int result = LibUsb.submitTransfer(transfer);		
		if (result != LibUsb.SUCCESS) throw new LibUsbException("Unable to submit transfer", result);
	}

}
