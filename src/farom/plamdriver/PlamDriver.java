/**
 * 
 */
package farom.plamdriver;

import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.List;

import javax.usb.UsbConst;
import javax.usb.UsbControlIrp;
import javax.usb.UsbDevice;
import javax.usb.UsbDeviceDescriptor;
import javax.usb.UsbDisconnectedException;
import javax.usb.UsbException;
import javax.usb.UsbHostManager;
import javax.usb.UsbHub;
import javax.usb.UsbServices;



/**
 * @author farom
 *
 */
public class PlamDriver {
	public static final short VENDOR_ID = 0x0547;
	public static final short PRODUCT_ID = 0x3303;
	
	private UsbDevice device;
	/**
	 * 
	 */
	public PlamDriver() {
		UsbServices services;
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
		}  
		
	}
	
	private void init(){
		// golden pass
		sendAndVerifyControl(0xc0,17,8190,0x0000,new byte[]{0x00,0x05,0x08});
		sendAndVerifyControl(0xc0,17,9216,0x0000,new byte[]{0x30,0x36,0x08});
		sendAndVerifyControl(0xc0,17,9218,0x0000,new byte[]{0x31,0x31,0x08});
		sendAndVerifyControl(0xc0,17,9220,0x0000,new byte[]{0x30,0x39,0x08});
		sendAndVerifyControl(0xc0,17,9222,0x0000,new byte[]{0x30,0x30,0x08});
		sendAndVerifyControl(0xc0,17,9224,0x0000,new byte[]{0x30,0x36,0x08});
		sendAndVerifyControl(0xc0,17,9226,0x0000,new byte[]{0x32,0x00,0x08});		
		sendAndVerifyControl(0x40,9,256,0x000f,new byte[0]);
		try {
		    Thread.sleep(100);                 //100 milliseconds
		} catch(InterruptedException ex) {
		    Thread.currentThread().interrupt();
		}		
		sendAndVerifyControl(0x40,9,512,0x000f,new byte[0]);
		sendAndVerifyControl(0xc0,17,8190,0x0000,new byte[]{0x00,0x05,0x08});
		sendAndVerifyControl(0x40,9,1024,0x0001,new byte[0]);
		sendAndVerifyControl(0x40,3,8963,0x0001,new byte[0]);
		try {
		    Thread.sleep(100);                 //100 milliseconds
		} catch(InterruptedException ex) {
		    Thread.currentThread().interrupt();
		}
		sendAndVerifyControl(0x40,3,13059,0x0000,new byte[0]);
		sendAndVerifyControl(0x40,3,8707,0x0001,new byte[0]);
		sendAndVerifyControl(0x40,3,13571,0x0000,new byte[0]);
		sendAndVerifyControl(0x40,3,50532,0x067f,new byte[0]);		
		sendAndVerifyControl(0x40,3,13699,0x0800,new byte[0]);
		sendAndVerifyControl(0x40,3,50654,0x0ddf,new byte[0]);
		sendAndVerifyControl(0x40,9,1024,0x0000,new byte[0]);
		sendAndVerifyControl(0x40,9,1024,0x0001,new byte[0]);
		
		sendAndVerifyControl(0x40,3,13443,0x800,new byte[0]);		
		sendAndVerifyControl(0x40,9,768,0x0000,new byte[0]);		
		sendAndVerifyControl(0x40,13,0,0x0001,new byte[0]);
		sendAndVerifyControl(0x40,13,0,0x0001,new byte[0]);
		sendAndVerifyControl(0x40,3,13827,0x0000,new byte[0]);
		sendAndVerifyControl(0x40,3,10035,0x0301,new byte[0]);
		
		sendAndVerifyControl(0x40,3,53256,0x00be,new byte[0]);
		sendAndVerifyControl(0x40,3,12547,0x0000,new byte[0]);
		
		sendAndVerifyControl(0x40,3,13443,0x0800,new byte[0]);
		sendAndVerifyControl(0x40,9,768,0x0000,new byte[0]);		
		sendAndVerifyControl(0x40,13,0,0x0001,new byte[0]);
		sendAndVerifyControl(0x40,13,0,0x0001,new byte[0]);
		sendAndVerifyControl(0x40,3,13827,0x0000,new byte[0]);
		sendAndVerifyControl(0x40,3,10035,0x0301,new byte[0]);
		
	}

	private boolean sendAndVerifyControl(int bmRequestType, int bRequest, int wIndex, int wValue, byte[] trueData){
		byte[] data;
		try {
			data = sendControl((byte)bmRequestType, (byte)bRequest, (short)wIndex, (short)wValue, (short)trueData.length);
		} catch (UsbDisconnectedException | UsbException e) {
			System.out.println("Exception raised for the following command:");
			System.out.println("  bRequest="+bRequest);
			System.out.println("  wIndex="+wIndex);
			System.out.println("  wValue="+wValue);
			System.out.println("  Exception message: "+ e.getLocalizedMessage());
			return false;
		}
		if(!Arrays.equals(data,trueData)){
			System.out.println("Wrong answer to the following command:");
			System.out.println("  bRequest="+bRequest+"="+((byte)bRequest));
			System.out.println("  wIndex="+wIndex+"="+((short)wIndex));
			System.out.println("  wValue="+wValue+"="+((short)wValue));
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
		}else{
			return true;
		}
	}
	
	private byte[] sendControl(byte bmRequestType, byte bRequest, short wIndex, short wValue, short dataLength) throws UsbDisconnectedException, UsbException{
		UsbControlIrp irp = device.createUsbControlIrp(bmRequestType, bRequest, wValue, wIndex);
		byte[] data = new byte[dataLength];
		irp.setData(data);
		device.syncSubmit(irp);
		return irp.getData();
	}
	
	@SuppressWarnings("unchecked")
	private UsbDevice findCamera(UsbHub hub)
	{
	    for (UsbDevice device : (List<UsbDevice>) hub.getAttachedUsbDevices())
	    {
	        UsbDeviceDescriptor desc = device.getUsbDeviceDescriptor();
	        if (desc.idVendor() == VENDOR_ID && desc.idProduct() == PRODUCT_ID) return device;
	        if (device.isUsbHub())
	        {
	            device = findCamera((UsbHub) device);
	            if (device != null) return device;
	        }
	    }
	    return null;
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) {
        new PlamDriver();

	}

}
