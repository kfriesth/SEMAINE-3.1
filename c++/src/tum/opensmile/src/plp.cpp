/*F******************************************************************************
 *
 * openSMILE - open Speech and Music Interpretation by Large-space Extraction
 *       the open-source Munich Audio Feature Extraction Toolkit
 * Copyright (C) 2008-2009  Florian Eyben, Martin Woellmer, Bjoern Schuller
 *
 *
 * Institute for Human-Machine Communication
 * Technische Universitaet Muenchen (TUM)
 * D-80333 Munich, Germany
 *
 *
 * If you use openSMILE or any code from openSMILE in your research work,
 * you are kindly asked to acknowledge the use of openSMILE in your publications.
 * See the file CITING.txt for details.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 ******************************************************************************E*/


/*  openSMILE component: cPlp

This component computes PLP and RASTA-PLP cepstral coefficients from a critical band spectrum (generated by the cMelspec component, for example).

The component is capable of performing the following processing steps:
   1) Take the natural logarithm of the critical band powers (doLog)
   2) RASTA filtering
   3) Computation of auditory spectrum (equal loudness curve and loudness compression)
   4) Inverse of the natural logarithm
   5) Inverse DFT to obtain autocorrelation coefficients
   6) Linear prediction analysis on autocorr. coeff.
   7) Computation of cepstral coefficients from lp coefficients
   8) Cepstral 'liftering'

 */


#include <plp.hpp>

#define MODULE "cPlp"


SMILECOMPONENT_STATICS(cPlp)

SMILECOMPONENT_REGCOMP(cPlp)
{
	SMILECOMPONENT_REGCOMP_INIT
	scname = COMPONENT_NAME_CPLP;
	sdescription = COMPONENT_DESCRIPTION_CPLP;

	// we inherit cVectorProcessor configType and extend it:
	SMILECOMPONENT_INHERIT_CONFIGTYPE("cVectorProcessor")

	SMILECOMPONENT_IFNOTREGAGAIN(
			//ct->setField("nameAppend", NULL, ");

			ct->setField("lpOrder","The order of the linear predictor (5th order is optimal according to Hermansky 1990, JASA)",5);
	ct->setField("nCeps","The number of cepstral coefficients (must be <= lpOrder, set to -1 for max. (=lpOrder))",-1);
	ct->setField("firstCC","The first cepstral coefficient to compute (set to 0 to include the 0th coefficient, which is defined as -log(1/lpcGain) )",1);
	ct->setField("lastCC","The last cepstral coefficient to compute (set to -1 to use nCeps, else lastCC will override nCeps!)",-1);

	ct->setField("doLog","Take the log of input bands (1=yes / 0=no)",1);
	ct->setField("doAud","Do auditory processing (equal loudness curve and loudness compression) (1=yes / 0=no)",1);

	ct->setField("RASTA","Perform RASTA (temporal) filtering (1=yes / 0=no)",0);
	ct->setField("newRASTA","Perform RASTA (temporal) filtering (more stable filter, Type-II, initial filtering only with FIR part; thanks to Chris Landsiedl for this code!) (1=enable / 0=disable) Note: this option (if set to 1) will disable the 'RASTA' option.",0);
	ct->setField("rastaUpperCutoff","Upper cut-off frequency of RASTA bandpass filter in Hz",29.0);
	ct->setField("rastaLowerCutoff","Lower cut-off frequency of RASTA bandpass filter in Hz",1.0);

	ct->setField("doInvLog","Apply inverse logarithm after power compression (1=yes / 0=no)",1);
	ct->setField("doIDFT","Apply I(nverse)DFT after power compression and inverse log (1=yes / 0=no)",1);
	ct->setField("doLP","Do lp analysis on autocorrelation function (1=yes / 0=no)",1);
	ct->setField("doLpToCeps","Convert lp coefficients to cepstral coefficients (1=yes / 0=no)",1);

	ct->setField("cepLifter","Parameter for cepstral 'liftering', set to 0.0 to disable cepstral liftering",0);
	ct->setField("compression","Compression factor for 'power law of hearing'",0.33);

	ct->setField("melfloor","Minimum value of melspectra when computing mfcc (will be forced to 1.0 when htkcompatible=1)",0.00000000093);
	ct->setField("htkcompatible","Set correct mel-floor and force HTK compatible PLP output (1/0 = yes/no)\n  htkcompatible == 1, forces the following settings:\n  - melfloor = 1.0 (signal scaling 0..32767*32767)\n  - append 0th coeff instead of having it as first value\n  - doAud = 1 , doLog=0 , doInvLog=0   (doIDFT, doLP, and doLpToCeps are not forced to 1, this enables generation of HTK compatible auditory spectra, etc. (these, of course, are not compatible, i.e. are not the same as HTK's PLP))\n  - the 0th audspec component is used as dc component in IDFT (else the DC component is zero)",1);
	)

	SMILECOMPONENT_MAKEINFO(cPlp);
}

SMILECOMPONENT_CREATE(cPlp)

//-----

cPlp::cPlp(const char *_name) :
cVectorProcessor(_name),
costable(NULL),
sintable(NULL),
eqlCurve(NULL),
acf(NULL), lpc(NULL), ceps(NULL),
htkcompatible(0),
rasta_buf_fir(NULL),
rasta_buf_iir(NULL)
{

}

void cPlp::fetchConfig()
{
	cVectorProcessor::fetchConfig();

	doLog = getInt("doLog");
	doAud = getInt("doAud");
	RASTA = getInt("RASTA");
	newRASTA = getInt("newRASTA");
	doInvLog = getInt("doInvLog");
	doIDFT = getInt("doIDFT");
	doLP = getInt("doLP");
	doLpToCeps = getInt("doLpToCeps");

	lpOrder = getInt("lpOrder");
	if (lpOrder <= 0) {
		lpOrder = 0;
		doLP = 0; doLpToCeps = 0;
	}
	nCeps = getInt("nCeps");

	firstCC = getInt("firstCC");
	lastCC = getInt("lastCC");
	if (firstCC > lpOrder)  { firstCC = lpOrder; nCeps = 1; lastCC = lpOrder; }
	else if (firstCC < 0) firstCC = 0;

	if (nCeps < 0) {
		nCeps = lpOrder-firstCC+1; // Note: (+1) because the 0th coefficient is extra
	}

	if (lastCC < 0) { lastCC = firstCC+nCeps-1; }
	else if (lastCC >= firstCC) {
		nCeps = lastCC-firstCC+1;
	}
	if (lastCC > lpOrder) {
		SMILE_IWRN(1,"number of last cepstral coefficient (%i) cannot be higher than lpOrder (%i)! (firstCC=%i, nCeps=%i)",lastCC,lpOrder,firstCC,nCeps);
		lastCC=lpOrder;
		nCeps = lastCC-firstCC+1;
	}
	SMILE_IDBG(2,"firstCC = %i",firstCC);
	SMILE_IDBG(2,"lastCC = %i",lastCC);
	SMILE_IDBG(2,"nCeps = %i",nCeps);

	if (nCeps == 0) { doLpToCeps = 0; }

	if (doLpToCeps) {
		doLP = 1;
	}
	if (doLP) {
		doIDFT = 1;
	}


	compression = (FLOAT_DMEM)getDouble("compression");
	if (compression < 0.0) compression = 0.0;
	SMILE_IDBG(2,"compression = %f",compression);

	cepLifter = (FLOAT_DMEM)getInt("cepLifter");
	if (cepLifter < 0) cepLifter = 0;
	SMILE_IDBG(2,"cepLifter = %f",cepLifter);

	melfloor = (FLOAT_DMEM)getDouble("melfloor");
	SMILE_IDBG(2,"melfloor = %f",melfloor);

	htkcompatible = getInt("htkcompatible");
	if (htkcompatible) {
		// htkcompatible == 1, forces the following settings:
		// - melfloor = 1.0 (signal scaling 0..32767*32767)
		// - append 0th coeff instead of having it as first value
		// - doAud = 1 , doLog=0 , doInvLog=0   (doIDFT, doLP, and doLpToCeps are not forced to 1, this enables generation of HTK compatible auditory spectra, etc. (these, of course, are not compatible, i.e. are not the same as HTK's PLP))
		// - use 0th audspec component as dc component in IDFT (else DC is zero)
		SMILE_IDBG(2,"HTK compatible output is enabled");
		melfloor = 1.0;
		doAud = 1; doLog = 0; doInvLog=0;
	}

	if (doLog != doInvLog) {
		SMILE_IWRN(1,"doLog (%i) != doInvLog (%i) , this makes no sense any may corrupt your features, are you sure you know what you are doing?",doLog,doInvLog);
	}

	if (RASTA || newRASTA) {
		doLog = 1; doInvLog = 1;
		rastaUpperCutoff = (FLOAT_DMEM)getDouble("rastaUpperCutoff");
		SMILE_IDBG(2,"rastaUpperCutoff = %f",rastaUpperCutoff);
		rastaLowerCutoff = (FLOAT_DMEM)getDouble("rastaLowerCutoff");
		SMILE_IDBG(2,"rastaUpperCutoff = %f",rastaUpperCutoff);
	}
	if (newRASTA) {RASTA = 0;}
}

/*
int cPlp::myConfigureInstance()
{
  int ret=1;
  ret *= cVectorProcessor::myConfigureInstance();
  if (ret == 0) return 0;


  return ret;
}
 */

int cPlp::dataProcessorCustomFinalise()
{
	//  Nfi = reader->getLevelNf();

	// allocate for multiple configurations..
	if (sintable == NULL) sintable = (FLOAT_DMEM**)multiConfAlloc();
	if (costable == NULL) costable = (FLOAT_DMEM**)multiConfAlloc();
	if (eqlCurve == NULL) eqlCurve = (FLOAT_DMEM**)multiConfAlloc();
	if (acf == NULL) acf = (FLOAT_DMEM**)multiConfAlloc();
	if (lpc == NULL) lpc = (FLOAT_DMEM**)multiConfAlloc();
	if (ceps == NULL) ceps = (FLOAT_DMEM**)multiConfAlloc();
	if (rasta_buf_fir == NULL) rasta_buf_fir = (FLOAT_DMEM**)multiConfAlloc();
	if (rasta_buf_iir == NULL) rasta_buf_iir = (FLOAT_DMEM**)multiConfAlloc();

	return cVectorProcessor::dataProcessorCustomFinalise();
}

/*
int cPlp::configureWriter(const sDmLevelConfig *c)
{
  if (c==NULL) return 0;

  // you must return 1, in order to indicate configure success (0 indicated failure)
  return 1;
}
 */

int cPlp::setupNamesForField(int i, const char*name, long nEl)
{
	// compute sin/cos tables and equal loudness weighting curve:
	initTables(nEl,getFconf(i),i);

	/*name="pspec";
  if ((nameAppend != NULL)&&(strlen(nameAppend)>0)) {
    addNameAppendField(name,nameAppend,nMfcc,firstMfcc);
  } else {
    writer->addField( name, nMfcc, firstMfcc);
  }
  return nMfcc;*/

	//same names as input fields  (maybe change the "nameAppend" depending on doIDFT flag...?)
	const char *_name=NULL;

	int dt = 0;
	void *_info = NULL;
	int _infoSize = 0;
	if (doLpToCeps) {
		if (RASTA || newRASTA) {
			_name = "RASTAPlpCC";
		} else {
			_name = "PlpCC";
		}
		dt = DATATYPE_CEPSTRAL;
		nEl = nCeps;
	} else if (doLP) {
		if (RASTA) {
			_name = "RASTAPlpc";
		} else if (newRASTA) {
			_name = "newRASTAPlpc";
		} else {
			_name = "Plpc";
		}
		dt = DATATYPE_COEFFICIENTS;
		nEl = lpOrder;
	} else if (doIDFT) {
		_name = "audAutoCor";
		dt = DATATYPE_ACF;
		nEl = nAuto;
	} else {
		_name = "audSpec";
		dt = DATATYPE_SPECTRUM_BANDS_MAG;
		const FrameMetaInfo * fmeta = reader->getFrameMetaInfo();
		if ((fmeta != NULL) && (i<fmeta->N)) {
			_info = fmeta->field[i].info;
			_infoSize = fmeta->field[i].infoSize;
		}
	}
	writer->setFieldInfo(i,dt,_info,_infoSize);

	return cVectorProcessor::setupNamesForField(i,_name,nEl);
}


// blocksize is size of mspec block (=nBands)
int cPlp::initTables( long blocksize, int idxc, int fidx )
{
	int i,m;
	FLOAT_DMEM *_costable = costable[idxc];
	FLOAT_DMEM *_sintable = sintable[idxc];
	FLOAT_DMEM *_eqlCurve = eqlCurve[idxc];
	FLOAT_DMEM *_acf = acf[idxc];
	FLOAT_DMEM *_lpc = lpc[idxc];
	FLOAT_DMEM *_ceps = ceps[idxc];
	FLOAT_DMEM *_rasta_buf_fir = rasta_buf_fir[idxc];
	FLOAT_DMEM *_rasta_buf_iir = rasta_buf_iir[idxc];

	nFreq = blocksize+2; // +DC + Nyquist...?
	if (doIDFT) {
		nAuto = lpOrder + 1;
		if (_costable != NULL) free(_costable);
		_costable = (FLOAT_DMEM *)malloc(sizeof(FLOAT_DMEM)*nAuto*nFreq);
		if (_costable == NULL) OUT_OF_MEMORY;

		// memory for acf:
		_acf = (FLOAT_DMEM*)malloc(sizeof(FLOAT_DMEM)*nAuto);
		// IDFT costable:
		FLOAT_DMEM a = (FLOAT_DMEM) M_PI / (FLOAT_DMEM)(nFreq-1);
		for (i=0; i<nAuto; i++) {
			int ib = i*nFreq;
			_costable[ib] = 1.0;
			for (m=1; m<(nFreq-1); m++) {
				_costable[m+ib] = (FLOAT_DMEM)( 2.0 * cos(a * (double)i * (double)m ) );
			}
			_costable[m+ib] = (FLOAT_DMEM)( cos(a * (double)i * (double)m ) );
		}
	}

	// memory for lp coefficients
	if (doLP) {
		_lpc = (FLOAT_DMEM*)malloc(sizeof(FLOAT_DMEM)*lpOrder);
	}

	// sintable for cepstral liftering
	if (doLpToCeps) {
		if (_sintable != NULL) free(_sintable);
		_sintable = (FLOAT_DMEM *)malloc(sizeof(FLOAT_DMEM)*nCeps);
		if (_sintable == NULL) OUT_OF_MEMORY;

		if (cepLifter > 0.0) {
			for (i=firstCC; i <= lastCC; i++) {
				_sintable[i-firstCC] = ((FLOAT_DMEM)1.0 + cepLifter/(FLOAT_DMEM)2.0 * sin((FLOAT_DMEM)M_PI*((FLOAT_DMEM)(i))/cepLifter));
			}
		} else {
			for (i=firstCC; i <= lastCC; i++) {
				_sintable[i-firstCC] = 1.0;
			}
		}
		_ceps = (FLOAT_DMEM*)malloc(sizeof(FLOAT_DMEM)*nCeps);
	}

	// equal loudness curve:
	if (doAud) {
		const FrameMetaInfo * fmeta = reader->getFrameMetaInfo();
		if ((fmeta != NULL)&&(fidx < fmeta->N)) {
			const FLOAT_DMEM * frq = (FLOAT_DMEM *)(fmeta->field[fidx].info);
			nScale = fmeta->field[fidx].infoSize / sizeof(FLOAT_DMEM);

			if (nScale != blocksize) {
				SMILE_IWRN(2,"number of frequency axis points (from info struct) [%i] is not equal to blocksize [%i] ! Field index: %i (check the processArrayFields option).",nScale,blocksize,fidx);
				nScale = MIN(nScale,blocksize);
			}

			_eqlCurve = (FLOAT_DMEM*)malloc(sizeof(FLOAT_DMEM)*nScale);
			int i;
			for (i=0; i<nScale; i++) {
				if (htkcompatible) {
					_eqlCurve[i] = (FLOAT_DMEM)smileDsp_equalLoudnessWeight_htk((double)frq[i]);
				} else {
					_eqlCurve[i] = (FLOAT_DMEM)smileDsp_equalLoudnessWeight((double)frq[i]);
				}
				if (doLog) {
					_eqlCurve[i] = log(_eqlCurve[i]);
					SMILE_IDBG(2,"log-eqlCurve (f=%f) = %f",frq[i],_eqlCurve[i]);
				} else {
					SMILE_IDBG(2,"lin-eqlCurve (f=%f) = %f",frq[i],_eqlCurve[i]);
				}
			}
		}
	}

	// rasta filter
	if (RASTA) {
		// IIR part
		rasta_iir = (FLOAT_DMEM)( 1.0 - sin(2.0 * M_PI * rastaLowerCutoff * reader->getLevelT() ) );

		// FIR part:
		FLOAT_DMEM om = (FLOAT_DMEM)cos( 2.0 * M_PI * rastaUpperCutoff * reader->getLevelT() );
		FLOAT_DMEM norm = (FLOAT_DMEM)sqrt( 10.0 * ( 32.0*om*om + 8.0) );
		rasta_fir[0] = (FLOAT_DMEM)( 2.0 / norm );
		rasta_fir[1] = (FLOAT_DMEM)( -4.0 * om / norm );
		rasta_fir[2] = 0.0;
		rasta_fir[3] = -rasta_fir[1];
		rasta_fir[4] = -rasta_fir[0];

		_rasta_buf_iir = (FLOAT_DMEM*)calloc(1,sizeof(FLOAT_DMEM)*blocksize);
		_rasta_buf_fir = (FLOAT_DMEM*)calloc(1,sizeof(FLOAT_DMEM)*blocksize*5);
		rasta_buf_fir_ptr = 0;
		rasta_init = 0;
	}
	if (newRASTA) {
		// IIR part
		rasta_iir = (FLOAT_DMEM)( 1.0 - sin(2.0 * M_PI * rastaLowerCutoff * reader->getLevelT() ) );

		// FIR part:
		FLOAT_DMEM om = (FLOAT_DMEM)cos( 2.0 * M_PI * rastaUpperCutoff * reader->getLevelT() );
		FLOAT_DMEM norm = (FLOAT_DMEM)sqrt( 10.0 * ( 32.0*om*om + 8.0) );
		rasta_fir[0] = (FLOAT_DMEM)( 2.0 / norm );
		rasta_fir[1] = (FLOAT_DMEM)( -4.0 * om / norm );
		rasta_fir[2] = 0.0;
		rasta_fir[3] = -rasta_fir[1];
		rasta_fir[4] = -rasta_fir[0];

		//      _rasta_buf_iir = (FLOAT_DMEM*)calloc(1,sizeof(FLOAT_DMEM)*blocksize);
		_rasta_buf_fir = (FLOAT_DMEM*)calloc(1,sizeof(FLOAT_DMEM)*blocksize*4);
		//      rasta_buf_fir_ptr = 0;
		rasta_init = 0;
	}

	acf[idxc] = _acf;
	lpc[idxc] = _lpc;
	ceps[idxc] = _ceps;

	costable[idxc] = _costable;
	eqlCurve[idxc] = _eqlCurve;
	sintable[idxc] = _sintable;

	rasta_buf_fir[idxc] = _rasta_buf_fir;
	rasta_buf_iir[idxc] = _rasta_buf_iir;

	return 1;
}



// a derived class should override this method, in order to implement the actual processing
int cPlp::processVectorFloat(const FLOAT_DMEM *src, FLOAT_DMEM *dst, long Nsrc, long Ndst, int idxi) // idxi=input field index
{
	int i,m;
	idxi = getFconf(idxi);
	FLOAT_DMEM *_costable = costable[idxi];
	FLOAT_DMEM *_sintable = sintable[idxi];
	FLOAT_DMEM *_eqlCurve = eqlCurve[idxi];
	FLOAT_DMEM *_acf = acf[idxi];
	FLOAT_DMEM *_lpc = lpc[idxi];
	FLOAT_DMEM *_ceps = ceps[idxi];
	FLOAT_DMEM *_rasta_buf_fir = rasta_buf_fir[idxi];
	FLOAT_DMEM *_rasta_buf_iir = rasta_buf_iir[idxi];

	FLOAT_DMEM *_src;
	_src = (FLOAT_DMEM*)malloc(sizeof(FLOAT_DMEM)*Nsrc);
	if (_src==NULL) OUT_OF_MEMORY;

	// compute log spectrum
	if (doLog) {
		for (i=0; i<Nsrc; i++) {
			if (src[i] < melfloor) _src[i] = log(melfloor);
			else _src[i] = (FLOAT_DMEM)log(src[i]);
			//if (!htkcompatible) _src[i] += 21;
		}
	} else {
		for (i=0; i<Nsrc; i++) {
			_src[i] = src[i];
		}
	}

	// rasta filtering
	if (RASTA) {
		for (i=0; i<Nsrc; i++) {
			FLOAT_DMEM sum;
			_rasta_buf_fir[i*5+rasta_buf_fir_ptr] = _src[i];
			sum = rasta_fir[0] * _src[i];
			// fir filter
			for (m=1; m<5; m++) {
				sum += rasta_fir[m] * _rasta_buf_fir[i*5+((5-m+rasta_buf_fir_ptr)%5)];
			}
			// iir filter
			sum += rasta_iir * _rasta_buf_iir[i];
			//printf("rasta_iir = %f\n",rasta_iir);
			//if (i==2) printf("sum (%i)(%i): %f (%f) [%f , %f]\n",rasta_init,i,sum,_src[i],rasta_iir,rasta_buf_iir[i]);
			_rasta_buf_iir[i] = sum;
			// save output

			if (rasta_init>=5) { _src[i] = sum; }
			else {_src[i] = 0; }
		}
		if (rasta_init<5) {  rasta_init++; }
		rasta_buf_fir_ptr = (rasta_buf_fir_ptr+1)%5;
	}
	if (newRASTA) {
		for (i=0; i<Nsrc; i++) {
			FLOAT_DMEM out;
			out = rasta_fir[0] * _src[i] + _rasta_buf_fir[i*4 + 0];
			_rasta_buf_fir[i*4 + 0] = rasta_fir[1] * _src[i] + _rasta_buf_fir[i*4 + 1] + (rasta_init>=5) * rasta_iir * out;
			_rasta_buf_fir[i*4 + 1] = rasta_fir[2] * _src[i] + _rasta_buf_fir[i*4 + 2];
			_rasta_buf_fir[i*4 + 2] = rasta_fir[3] * _src[i] + _rasta_buf_fir[i*4 + 3];
			_rasta_buf_fir[i*4 + 3] = rasta_fir[4] * _src[i];

			//if (i==2) printf("sum (%i)(%i): %f (%f) [%f]\n",rasta_init,i,out,_src[i],rasta_iir);

			if (rasta_init>=5) { _src[i] = out; }
			else {_src[i] = 0; }
		}
		if (rasta_init<5) {  rasta_init++; }
//		rasta_buf_fir_ptr = (rasta_buf_fir_ptr+1)%5;
	}


	// Auditory pre-emphasis and loudness compression
	if (doAud) {

		if (doLog) {
			// add equal loudness curve  // TODO: check Nsrc == eqlCurve size -> i.e. nScale <= Nsrc!
			for (i=0; i<nScale; i++) {
				_src[i] += _eqlCurve[i];
			}
			// power law of hearing, loudness compression in log-domain is multiplication with compression factor:
			for (i=0; i<Nsrc; i++) {
				_src[i] *= compression;
			}
		} else {
			// multiply with equal loudness curve  // TODO: check Nsrc == eqlCurve size -> i.e. nScale <= Nsrc!
			for (i=0; i<nScale; i++) {
				if (_src[i] < melfloor) _src[i] = melfloor;
				_src[i] *= _eqlCurve[i];
			}
			// power law of hearing, loudness compression in linear domain:
			for (i=0; i<Nsrc; i++) {
				_src[i] = (FLOAT_DMEM)pow((double)_src[i] , (double)compression);
			}
		}

	}

	// inverse log
	if (doInvLog) {
		for (i=0; i<Nsrc; i++) {
			//if (!htkcompatible) _src[i] -= 21;
			_src[i] = exp(_src[i]);
		}
	}

	// inverse DFT -> autocorrelation (since input spectra are power spectra)
	// Note: for inverse DFT we require a DC and a Nyquist component, we thus duplicate the first and last component
	if (doIDFT) {  // TODO: check nAuto<=nDst!
		for (i=0; i<nAuto; i++) {
			double tmp = 0;
			if (htkcompatible) { tmp = (double)_costable[i*nFreq] * (double)_src[0]; }

			for (m=1; m<nFreq-1; m++) {
				tmp += (double)_costable[m+i*nFreq] * (double)_src[m-1];
			}
			tmp += (double)_costable[m+i*nFreq] * (double)_src[nFreq-3];
			_acf[i] = (FLOAT_DMEM)(tmp / (2.0*(nFreq-1)));
		}

		// linear prediction analysis on ACF
		FLOAT_DMEM lpGain=0.0;
		if (doLP) {
			smileDsp_calcLpcAcf(_acf, _lpc, lpOrder, &lpGain, NULL);

			// convert lp filter coefficients to cepstral representation
			if (doLpToCeps) {
				if (lpGain <= 0) {
					SMILE_IERR(1,"negative or zero lpc gain! forcing lpcGain = 1.0!");
					lpGain = (FLOAT_DMEM)1.0;
				}

				// lp to ceps
				FLOAT_DMEM *__ceps = _ceps;
				if (!htkcompatible && (firstCC==0)) __ceps++;
				FLOAT_DMEM zeroth = smileDsp_lpToCeps(_lpc,lpOrder,lpGain,__ceps,firstCC,lastCC);
				if (firstCC==0) {
					if (!htkcompatible) { _ceps[0] = zeroth; }
					else { _ceps[nCeps-1] = zeroth; }
				}

				// cepstral liftering:
				FLOAT_DMEM factor;
				if (cepLifter > 0.0) {
					factor = (FLOAT_DMEM)sqrt((double)2.0/(double)(Nsrc));
				}
				for (i=firstCC; i<=lastCC; i++) {
					int i0 = i-firstCC;
					int i1 = i0;
					if (htkcompatible && (firstCC==0)) {
						if (i==lastCC) { i1 = 0; }
						else { i1 += 1; }
					}
					if (cepLifter > 0.0) {
						dst[i0] = _ceps[i0] * _sintable[i1];
					} else {
						dst[i0] = _ceps[i0];// * _sintable[i1] * factor;
					}
				}

			} else { // save lp coeffs to dst[]
				for (i=0; i<lpOrder; i++) {
					dst[i] = _lpc[i];
				}
			}
		} else { // save data in acf[] to dst[]
			for (i=0; i<nAuto; i++) {
				dst[i] = _acf[i];
			}
		}
	} else { // no IDFT , save spectrum in _src[] to dst[]
		for (i=0; i<Nsrc; i++) {
			dst[i] = _src[i];
		}
	}

	free(_src);

	return 1;
}

cPlp::~cPlp()
{
	multiConfFree(costable);
	multiConfFree(sintable);
	multiConfFree(eqlCurve);
	multiConfFree(acf);
	multiConfFree(lpc);
	multiConfFree(ceps);
	multiConfFree(rasta_buf_fir);
	multiConfFree(rasta_buf_iir);
}

