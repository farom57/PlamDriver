/**
 * 
 */
package farom.plamdriver;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
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
public class PlamDriverBak implements TransferCallback{
	public static final short VENDOR_ID = 0x0547;
	public static final short PRODUCT_ID = 0x3303;
	private static final long CTRL_TIMEOUT = 100;
	private static final int IMG_BYTE_SIZE = 640*480*2;
	private static final byte BULK_ENDPOINT = (byte) 0x82;
	private static final double DURATION_COEF = 5923;//7692;//59171.5978836;
	private static final short IMGCTRL_THIGH = 6;
	private static final short IMGCTRL_TLOW = 7;
	

	//private UsbDevice device;
	private Device device;
	private DeviceHandle handle;
	private EventHandlingThread thread;
	private boolean captureOngoing = false;
	private int img = 0;

	/**
	 * 
	 */
	public PlamDriverBak() {
		Context context = new Context();
		int result = LibUsb.init(context);
		thread = new EventHandlingThread();
		thread.start();
		if (result != LibUsb.SUCCESS){
			System.out.println("Unable to initialize libusb: "+ result);
			return;
		}
		device = findDevice(VENDOR_ID, PRODUCT_ID);
		if(device==null){
			System.out.println("Device not found");
			return;
		}
		handle = new DeviceHandle();
		result = LibUsb.open(device, handle);
		if (result != LibUsb.SUCCESS){
			System.out.println("Unable to open USB device: "+ result);
			return;
		}else{
			System.out.println("Device found and opened !");
		}
		try
		{
			ConfigDescriptor descriptor = new ConfigDescriptor();
			result = LibUsb.getConfigDescriptor(device, (byte)0, descriptor);
			if (result != LibUsb.SUCCESS){
				System.out.println("Unable to get config descriptor: "+ result);
			}else{
				System.out.println("num interfaces: "+descriptor.bNumInterfaces());
			}
			
			
			result = LibUsb.claimInterface(handle, 0);
			if (result != LibUsb.SUCCESS) throw new LibUsbException("Unable to claim interface", result);
			try
			{
				/*ByteBuffer buffer = ByteBuffer.allocateDirect(3);
				//buffer.put(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
				int transfered = LibUsb.controlTransfer(handle, (byte)0xc0,(byte)17,(short)0,(short)8190,buffer,100);
				if (transfered < 0) throw new LibUsbException("Control transfer failed", transfered);
				System.out.println(transfered + " bytes sent");
				System.out.println(buffer.get(1));*/
				init();
			}
			finally
			{
			    result = LibUsb.releaseInterface(handle, 0);
			    if (result != LibUsb.SUCCESS) throw new LibUsbException("Unable to release interface", result);
			}
		}
		finally
		{
			thread.abort();
			try {
				thread.join();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		    LibUsb.close(handle);
		}
		//System.out.println("Device found: "+device.);
		
		LibUsb.exit(context);
		
		
		/*UsbServices services;
		try {
			services = UsbHostManager.getUsbServices();
			device = findCamera(services.getRootUsbHub());
			System.out.println(device.getManufacturerString());

			init();

		} catch (SecurityException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (UsbException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (UnsupportedEncodingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (UsbDisconnectedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}*/

	}
	
	public Device findDevice(short vendorId, short productId)
	{
	    // Read the USB device list
	    DeviceList list = new DeviceList();
	    int result = LibUsb.getDeviceList(null, list);
	    if (result < 0) throw new LibUsbException("Unable to get device list", result);

	    try
	    {
	        // Iterate over all devices and scan for the right one
	        for (Device device: list)
	        {
	            DeviceDescriptor descriptor = new DeviceDescriptor();
	            result = LibUsb.getDeviceDescriptor(device, descriptor);
	            if (result != LibUsb.SUCCESS) throw new LibUsbException("Unable to read device descriptor", result);
	            if (descriptor.idVendor() == vendorId && descriptor.idProduct() == productId) return device;
	        }
	    }
	    finally
	    {
	        // Ensure the allocated device list is freed
	        LibUsb.freeDeviceList(list, true);
	    }

	    // Device not found
	    return null;
	}

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
		sendAndVerifyControl(0x40, 3, 8963, 0x0001, new byte[0]);
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
		capture(0,1);
		
		sendAndVerifyControl(0x40, 3, 13827, 0x0000, new byte[0]);
		sendAndVerifyControl(0x40, 3, 10035, 0x0301, new byte[0]);

		while(captureOngoing == true){
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			System.out.print(".");
		}
		System.out.println("init ok");
		setGain(750);
		
		double duration = 10;
		duration = duration * DURATION_COEF;
		int duration_int = (int) duration;
		short duration_high = (short) (duration_int / 4096);
		short duration_low = (short) (duration_int - duration_high * 4096);
		sendImgCtrl(IMGCTRL_THIGH, duration_high);
		capture(1,10);
		sendImgCtrl(IMGCTRL_TLOW, duration_low);
		for(int i = 0; i<10; i++){
			while(captureOngoing == true){
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				System.out.print(".");
			}
			capture(i,10);
		}
		
	}
	
	private void capture(int img, double duration){

		
		ByteBuffer buffer = ByteBuffer.allocateDirect(IMG_BYTE_SIZE);
		//IntBuffer transfered = IntBuffer.allocate(1);
		
		captureOngoing = true;
		
		
//		try {
//			Thread.sleep(1000); // 100 milliseconds
//		} catch (InterruptedException ex) {
//			Thread.currentThread().interrupt();
//		}
		Transfer transfer = LibUsb.allocTransfer();
		LibUsb.fillBulkTransfer(transfer, handle, BULK_ENDPOINT, buffer, this, null, (long) (duration*1000 + 2000));
		int result = LibUsb.submitTransfer(transfer);		
		if (result != LibUsb.SUCCESS) throw new LibUsbException("Unable to submit transfer", result);
		
		//System.out.println("image captured!");		
	}

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

	
	private byte[] sendControl(byte bmRequestType, byte bRequest, short wIndex, short wValue, short dataLength) throws LibUsbException {
		if(bmRequestType >= 0 && dataLength!=0){ // host to device request with undefined data
			System.out.println("host to device request with undefined data: bmRequestType = " + bmRequestType +" and dataLength = " + dataLength);
		}
		
		ByteBuffer buffer = ByteBuffer.allocateDirect(dataLength);
		int transfered = LibUsb.controlTransfer(handle, bmRequestType,bRequest,wValue,wIndex,buffer,CTRL_TIMEOUT);
		if (transfered != dataLength) throw new LibUsbException("Control transfer failed", transfered);
		
		if(dataLength>0){
			byte array[] = new byte[dataLength];
			for(int i = 0; i<dataLength; i++){
				array[i]=buffer.get(i);
			}
			return array;
		}else{
			return new byte[0];
		}
	}

	
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		short wi;
		short wv=0x0444;
		wi = (short) ((short) ((wv ^ (short)0x33)<<4)|(short)6);
		System.out.printf("wIndex: %d(0x%04X)\n",(int)wi,(int)wi);
		System.out.printf("wValue: %d(0x%04X)\n",wv,wv);
		new PlamDriverBak();

	}
	
	private void sendImgCtrl(short type, short value){
		short wIndex = (short) ((short) ((value ^ (short)0x33)<<4)|type);
		System.out.printf("wIndex0 = %04X\n",wIndex);
		wIndex = (short) (wIndex<<8|((wIndex>>8)&0xFF));
		System.out.printf("value = %04X wIndex = %04X\n",value,wIndex);
		sendControl((byte)0x40, (byte)0x3, wIndex, value, (short)0);
		//sendControl((byte)0x40, (byte)0x3, wIndex, (short)0, (short)0);
		
	}
	
	private void setGain(int gain){
		if(gain > 1023 || gain < 0){
			throw new IllegalArgumentException("The gain must be between 0 and 1023. Current value: " + gain);
		}
		sendImgCtrl((short)0x00, (short)gain);
	}
	
	private void setBlack(int level){
		if(level > 255 || level < 0){
			throw new IllegalArgumentException("The black level must be between 0 and 255. Current value: " + level);
		}
		sendImgCtrl((short)0x01, (short)level);
	}
	
	class EventHandlingThread extends Thread
	{
	    /** If thread should abort. */
	    private volatile boolean abort;

	    /**
	     * Aborts the event handling thread.
	     */
	    public void abort()
	    {
	        this.abort = true;
	    }

	    @Override
	    public void run()
	    {
	        while (!this.abort)
	        {
	            int result = LibUsb.handleEventsTimeout(null, 250000);
	            if (result != LibUsb.SUCCESS)
	                throw new LibUsbException("Unable to handle events", result);
	        }
	    }
	}

	@Override
	public void processTransfer(Transfer transfer) {
		System.out.println(transfer.actualLength() + " bytes received");
        
        captureOngoing = false;
        
        if (transfer.actualLength()!=IMG_BYTE_SIZE) {
        	System.out.println("Bulk transfer incomplete "+transfer.actualLength()+"/"+IMG_BYTE_SIZE);
        	return;
        	//throw new LibUsbException("Bulk transfer incomplete "+transfer.actualLength()+"/"+IMG_BYTE_SIZE, transfer.status());
        }
		ByteBuffer buffer = transfer.buffer();
		float[][] data1 = new float[480][640];
		for(int i = 0; i<480; i++){
			for(int j = 0; j<640; j++){
				data1[i][j]=(buffer.get((i*640+(639-j))*2)&0xFF)*256+(buffer.get((i*640+(639-j))*2+1)&0xFF);//i+2*j;//256.f*(data[(i*640+j)*2]&0xFF)+1.f*(data[(i*640+j)*2+1]&0xFF);//+data[(i*640+j)*2+1]&0xFF;
				
			}
		}
		
		LibUsb.freeTransfer(transfer);
		
		
		try {
			Fits f1 = new Fits();
			f1.addHDU(FitsFactory.HDUFactory(data1));
			BufferedFile bf1 = new BufferedFile("img"+img+".fits", "rw");
			f1.write(bf1);
			bf1.close();
			f1.close();
			img++;
			
			
			
		} catch (FitsException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		System.out.println("image captured!");
		
	}

}
